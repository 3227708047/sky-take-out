package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.constant.AutoFillConstant;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

@Aspect
@Component
@Slf4j
public class AutoFillAspect {

    /**
     * 切入点
     */
    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFillPointCut(){
    }


    @Before("autoFillPointCut()")
    public void AutoFill(JoinPoint joinPoint){
        log.info("开始进行数据填充");

        //获取到当前被拦截的方法上的数据库操作类型
        MethodSignature signature = (MethodSignature)joinPoint.getSignature();//方法签名对象
        AutoFill annotation = signature.getMethod().getAnnotation(AutoFill.class);//获取方法上的注解对象
        OperationType value = annotation.value();//获取数据库操作类型

        //获取到当前被拦截的方法都参数--实体对象
        Object[] args = joinPoint.getArgs();
        if(args.length == 0 || args == null) return ;
        Object entity = args[0];
        //准备赋值的数据
        LocalDateTime now = LocalDateTime.now();
        Long currentId = BaseContext.getCurrentId();
        //根据当前不同的操作类型，为对应的属性通过反射来赋值
        if(value == OperationType.INSERT){
            //为四个字段赋值
            try {
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);
                Method setCreateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);

                //通过反射为对象赋值
                setUpdateTime.invoke(entity,now);
                setCreateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
                setCreateUser.invoke(entity,currentId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }else if(value == OperationType.UPDATE){
            try {
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                //通过反射为对象赋值
                setUpdateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
