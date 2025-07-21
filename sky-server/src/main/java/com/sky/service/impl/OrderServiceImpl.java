package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private UserMapper userMapper;
    @Value("${sky.shop.address}")
    private String shopAddress;
    @Value("${sky.baidu.ak}")
    private String ak;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //异常情况的处理（收货地址为空、超出配送范围、购物车为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //检查用户的收获地址是否超出配送范围
//        checkOutOfRange(addressBook.getCityName()+addressBook.getDistrictName()+addressBook.getDetail());

        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        //查询当前用户的购物车数据
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setUserId(userId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());

        //向订单表插入1条数据
        orderMapper.insert(order);

        //订单明细数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }

        //向明细表插入n条数据
        orderDetailMapper.insertBatch(orderDetailList);

        //清理购物车中的数据
        shoppingCartMapper.deleteByUserId(userId);

        //封装返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();

        return orderSubmitVO;
    }



    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new OrderBusinessException("用户不存在");
        }

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        //通过websocket向客户端推送消息 type orderId content
        Map map = new HashMap();
        map.put("type", 1);//1表示来单提醒 2表示客户催单
        map.put("orderId", orders.getId());
        map.put("content","订单号："+outTradeNo);
        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     *  历史订单查询
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    @Override
    public PageResult getHistoryOrders(Integer page, Integer pageSize, Integer status) {
        //设置分页
        PageHelper.startPage(page, pageSize);
        Long userId = BaseContext.getCurrentId();
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(userId);
        ordersPageQueryDTO.setStatus(status);
        //分页条件查询
        Page<Orders> orders = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList = new ArrayList<>();

        if(orders != null && orders.getTotal()>0){
            for (Orders order : orders) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(order,orderVO);
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(order.getId());
                orderVO.setOrderDetailList(orderDetails);
                orderVOList.add(orderVO);
            }
        }


        return new PageResult(orders.getTotal(),orderVOList);
    }

    /**
     * 获取订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO getOrderDetail(Long id) {
        OrderVO orderVO = new OrderVO();
        Orders orders=orderMapper.getById(id);
        BeanUtils.copyProperties(orders,orderVO);
        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());
        orderVO.setOrderDetailList(orderDetails);


        return orderVO;
    }

    /**
     * 取消订单
     * @param id
     * @return
     **/
    @Override
    public void cancelOrder(Long id) throws Exception {
        //查询订单
        Orders orderDB = orderMapper.getById(id);
        if(orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if(orderDB.getStatus().equals(Orders.CANCELLED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(orderDB.getId());
        if (orderDB.getStatus()>Orders.TO_BE_CONFIRMED){
            weChatPayUtil.refund(
                    orderDB.getNumber(),
                    orderDB.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01)
            );
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelTime(LocalDateTime.now());
        orders.setCancelReason("用户取消");
        orderMapper.update(orders);

    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void OrderAgain(Long id) {
        Long userId = BaseContext.getCurrentId();
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> pages = orderMapper.pageQuery(ordersPageQueryDTO);
        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> list = getOrderVOList(pages);
        return new PageResult(pages.getTotal(), list);
    }

    /**
     * 订单数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        int TO_BE_CONFIRMED=orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        int CONFIRMED=orderMapper.countStatus(Orders.CONFIRMED);
        int DELIVERY_IN_PROGRESS=orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(CONFIRMED);
        orderStatisticsVO.setToBeConfirmed(TO_BE_CONFIRMED);
        orderStatisticsVO.setDeliveryInProgress(DELIVERY_IN_PROGRESS);
        return orderStatisticsVO;
    }




    private List<OrderVO> getOrderVOList(Page<Orders> pages) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> list = pages.getResult();

        if(list!= null){
            for (Orders order : list) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(order, orderVO);
                String orderDishes = getOrderDishesStr(order);
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    private String getOrderDishesStr(Orders order) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(order.getId());
        List<String> OrderDishes = orderDetailList.stream().map(orderDetail -> {
            String orderDishes = orderDetail.getName() + "*" + orderDetail.getNumber() + "份";
            return orderDishes;
        }).collect(Collectors.toList());
        return String.join("", OrderDishes);
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Long orderId = ordersConfirmDTO.getId();
        Orders orders = orderMapper.getById(orderId);
        orders.setStatus(Orders.CONFIRMED);
        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        Long id = ordersRejectionDTO.getId();
        Orders orderDB = orderMapper.getById(id);
        if(orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if(orderDB.getStatus().equals(Orders.CANCELLED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(orderDB.getId());
        if (orderDB.getStatus()>Orders.TO_BE_CONFIRMED){
            weChatPayUtil.refund(
                    orderDB.getNumber(),
                    orderDB.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01)
            );
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelTime(LocalDateTime.now());
        orders.setCancelReason(ordersRejectionDTO.getRejectionReason());
        orderMapper.update(orders);
    }

    /**
     * 订单取消
     * @param orderCancelDTO
     * @throws Exception
     */
    @Override
    public void cancelOrderDTO(OrdersCancelDTO orderCancelDTO) throws Exception {
        //查询订单
        Orders orderDB = orderMapper.getById(orderCancelDTO.getId());
        if(orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if(orderDB.getStatus().equals(Orders.CANCELLED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(orderDB.getId());
        if (orderDB.getStatus()>Orders.TO_BE_CONFIRMED){
            weChatPayUtil.refund(
                    orderDB.getNumber(),
                    orderDB.getNumber(),
                    new BigDecimal(0.01),
                    new BigDecimal(0.01)
            );
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelTime(LocalDateTime.now());
        orders.setCancelReason(orderCancelDTO.getCancelReason());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void deliveryOrder(Long id) {
        Orders orderDB = orderMapper.getById(id);
        if(orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if(orderDB.getStatus().equals(Orders.CANCELLED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if(orderDB.getStatus().equals(Orders.CONFIRMED)){
            Orders orders = new Orders();
            orders.setId(orderDB.getId());
            orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
            orders.setDeliveryTime(LocalDateTime.now());
            orderMapper.update(orders);
        }
    }

    /**
     * 订单完成
     * @param id
     * @return
     **/
    @Override
    public void completeOrder(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 催单
     * @param id
     */
    @Override
    public void reminder(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在，并且状态为4
        if(ordersDB == null ){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Map map = new HashMap();
        map.put("type",2); //1表示来单提醒 2表示客户催单
        map.put("orderId",id);
        map.put("content","订单号："+ordersDB.getNumber());

        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    /**
     * 检查客户的收货地址是否超出配送范围
     * @param address
     */
    private void checkOutOfRange(String address){
        Map map = new HashMap();
        map.put("address", shopAddress);
        map.put("output", "json");
        map.put("ak", ak);

        //获取店铺的经纬坐标
        String shopCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        JSONObject jsonObject = JSON.parseObject(shopCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new RuntimeException("获取店铺经纬度坐标失败");
        }
        //数据解析
        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");
        //店铺经纬度坐标
        String shopLngLat = lat + "," + lng;

        map.put("address",address);
        //获取用户收货地址的经纬度坐标
        String userCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        jsonObject = JSON.parseObject(userCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("收货地址解析失败");
        }

        //数据解析
        location = jsonObject.getJSONObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");
        //用户收货地址经纬度坐标
        String userLngLat = lat + "," + lng;

        map.put("origin",shopLngLat);
        map.put("destination",userLngLat);
        map.put("steps_info","0");

        //路线规划
        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);

        jsonObject = JSON.parseObject(json);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("配送路线规划失败");
        }

        //数据解析
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("routes");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");

        if(distance > 5000){
            //配送距离超过5000米
            throw new OrderBusinessException("超出配送范围");
        }
    }


}
