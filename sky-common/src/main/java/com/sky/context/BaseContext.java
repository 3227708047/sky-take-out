package com.sky.context;

public class BaseContext {

    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static Long getCurrentId() {
//        return threadLocal.get();
        return 5L;
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }

}
