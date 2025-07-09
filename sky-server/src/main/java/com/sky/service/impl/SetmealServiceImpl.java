package com.sky.service.impl;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.setmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


/**
 * 套餐业务实现
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private setmealDishMapper SetmealDishMapper;

    /**
     * 条件查询
     * @param setmeal
     * @return
     */
    public List<Setmeal> list(Setmeal setmeal) {
        List<Setmeal> list = setmealMapper.list(setmeal);
        return list;
    }

    /**
     * 根据id查询菜品选项
     * @param id
     * @return
     */
    @Override
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemById(id);
    }

    /**
     * 新增套餐
     * @param setmealDTO
     */
    @Override
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        //向套餐中插入数据
        setmealMapper.insert(setmeal);
        //获取套餐id
        Long setmealId = setmeal.getId();
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if(setmealDishes != null && setmealDishes.size() > 0){
            setmealDishes.forEach(setmealDish -> {
                setmealDish.setSetmealId(setmealId);
            });
            //保存套餐和菜品的关联关系，动态sql批量插入
            SetmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        int pageNum = setmealPageQueryDTO.getPage();
        int pageSize = setmealPageQueryDTO.getPageSize();

        //需要在查询功能之前开启分页功能：当前页的页码 每页显示的条数
        PageHelper.startPage(pageNum, pageSize);
        //这个方法有返回值为Page对象，里面保存的是分页之后的相关数据
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        //封装到PageResult中:总记录数 当前页数据集合
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public void deleteBatch(List<Long> ids) {
        //判断当前套餐是否能够删除---是否存在起售中的套餐？
        //思路：便利获取传入的id，根据id查询套餐setmeal中的status字段，0 停售 1 起售
        ids.forEach(id -> {
            Setmeal setmeal = setmealMapper.getById(id);
            if (StatusConstant.ENABLE == setmeal.getStatus()) throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
        });
        //思路：套餐表和菜品表是多对多关系，把整个套餐都删除了，那么关系表中保存的套餐对应的菜品数据也会被删除掉
        ids.forEach(setmealId -> {
            //删除套餐表中的数据
            setmealMapper.deleteById(setmealId);
            //删除套餐和菜品关系表中的数据
            setmealMapper.deleteBySetmealId(setmealId);
        });
    }

    /**
     * 根据id查询套餐数据
     * @param id
     * @return
     */
    @Override
    public SetmealVO getByIdWithDish(Long id) {
        //根据id查询套餐表数据
        Setmeal setmeal = setmealMapper.getById(id);

        //根据id查询餐菜品关系表数据
        List<SetmealDish> setmealDishes=SetmealDishMapper.getBySetmealId(id);

        //封装返回结果
        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal,setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);
        return setmealVO;
    }

    /**
     * 修改套餐
     * @param setmealDTO
     */
    @Override
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        //修改套餐表数据，执行update
        setmealMapper.update(setmeal);

        //获取生成的套餐id
        //套餐菜品关系表的setmeal_id页面不能传递
        Long setmealId = setmealDTO.getId();//新增套餐时的sql获取主键值
        //删除套餐和菜品的关联关系，操作setmeal_dish表，执行delete
        SetmealDishMapper.deleteBySetmealId(setmealId);//删除套餐已经实现了

        //获取页面传来的套餐和菜品关系表数据
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });
        //重新插入套餐和菜品关系表数据，操作setmeal_dish表，执行insert
        SetmealDishMapper.insertBatch(setmealDishes);//新增套餐时已经实现了
    }

    @Override
    public void startOrStop(Integer status, Long id) {
        if(status.equals(StatusConstant.ENABLE)){
            List<Dish> dishList=dishMapper.getBySetmealId(id);
            if(dishList!=null && dishList.size()>0){
                dishList.forEach(dish -> {
                    //套餐中包含菜品，如果这个菜品的状态为禁用，则抛出异常
                    if(dish.getStatus().equals(StatusConstant.DISABLE)){
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }

        Setmeal setmeal = Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

}
