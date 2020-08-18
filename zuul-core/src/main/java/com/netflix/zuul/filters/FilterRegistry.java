package com.netflix.zuul.filters;


import com.netflix.zuul.ZuulFilter;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 过滤器注册工厂
 *
 * @author mhawthorne
 */
public class FilterRegistry {
    /**
     * 懒汉模式单例
     */
    private static final FilterRegistry INSTANCE = new FilterRegistry();

    public static final FilterRegistry instance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, ZuulFilter> filters = new ConcurrentHashMap<String, ZuulFilter>();

    /**
     * 反射破单例
     */
    private FilterRegistry() {
    }

    /**
     * Map 的包装
     *
     * @param key 移除
     * @return ZuulFilter
     */
    public ZuulFilter remove(String key) {
        return this.filters.remove(key);
    }

    /**
     * Map 的包装
     *
     * @param key 获取Key
     * @return ZuulFilter
     */
    public ZuulFilter get(String key) {
        return this.filters.get(key);
    }

    /**
     * 新增
     *
     * @param key    KEY
     * @param filter 拦截器
     */
    public void put(String key, ZuulFilter filter) {
        this.filters.putIfAbsent(key, filter);
    }

    /**
     * MAP 的包装
     *
     * @return 注册的拦截器的数目
     */
    public int size() {
        return this.filters.size();
    }

    /**
     * 获取全部拦截器
     *
     * @return 全部拦截器
     */
    public Collection<ZuulFilter> getAllFilters() {
        return this.filters.values();
    }

}
