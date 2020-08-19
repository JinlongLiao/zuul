/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul;

import com.netflix.zuul.filters.FilterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This class is one of the core classes in Zuul. It compiles, loads from a File, and checks if source code changed.
 * It also holds ZuulFilters by filterType.
 * 加载过滤器
 *
 * @author Mikey Cohen
 * Date: 11/3/11
 * Time: 1:59 PM
 */
public class FilterLoader {
    /**
     * 懒汉单例
     */
    final static FilterLoader INSTANCE = new FilterLoader();

    private static final Logger LOG = LoggerFactory.getLogger(FilterLoader.class);

    private final ConcurrentHashMap<String, Long> filterClassLastModified = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, String> filterClassCode = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> filterCheck = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, List<ZuulFilter>> hashFiltersByType = new ConcurrentHashMap<String, List<ZuulFilter>>();

    private FilterRegistry filterRegistry = FilterRegistry.instance();

    static DynamicCodeCompiler COMPILER;

    static FilterFactory FILTER_FACTORY = new DefaultFilterFactory();

    /**
     * Sets a Dynamic Code Compiler
     * 设置动态编译器 ，（相同Groovy 动态语言可以实时改变后 加载入JVM 来动态改变过滤器）
     *
     * @param compiler
     */
    public void setCompiler(DynamicCodeCompiler compiler) {
        COMPILER = compiler;
    }

    // overidden by tests
    public void setFilterRegistry(FilterRegistry r) {
        this.filterRegistry = r;
    }

    /**
     * Sets a FilterFactory
     *
     * @param factory
     */
    public void setFilterFactory(FilterFactory factory) {
        FILTER_FACTORY = factory;
    }

    /**
     * @return Singleton FilterLoader
     */
    public static FilterLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Given source and name will compile and store the filter if it detects that the filter code has changed or
     * the filter doesn't exist. Otherwise it will return an instance of the requested ZuulFilter
     *
     * @param sCode source code
     * @param sName name of the filter
     * @return the ZuulFilter
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public ZuulFilter getFilter(String sCode, String sName) throws Exception {

        if (filterCheck.get(sName) == null) {
            filterCheck.putIfAbsent(sName, sName);
            if (!sCode.equals(filterClassCode.get(sName))) {
                LOG.info("reloading code " + sName);
                filterRegistry.remove(sName);
            }
        }
        ZuulFilter filter = filterRegistry.get(sName);
        if (filter == null) {
            Class clazz = COMPILER.compile(sCode, sName);
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
            }
        }
        return filter;

    }

    /**
     * @return the total number of Zuul filters
     */
    public int filterInstanceMapSize() {
        return filterRegistry.size();
    }


    /**
     * From a file this will read the ZuulFilter source code, compile it, and add it to the list of current filters
     * a true response means that it was successful.
     * <p>
     * <p>
     * 从文件中将读取ZuulFilter源代码，对其进行编译，然后将其添加到当前过滤器列表中
     * 真实的响应表示它已成功。
     *
     * @param file
     * @return true if the filter in file successfully read, compiled, verified and added to Zuul
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws IOException
     */
    public boolean putFilter(File file) throws Exception {
        String sName = file.getAbsolutePath() + file.getName();
        /**
         * 之前已加载，且文件的修改时间与之前不同，重新载入 （Groovy的特权）
         */
        if (filterClassLastModified.get(sName) != null && (file.lastModified() != filterClassLastModified.get(sName))) {
            LOG.debug("reloading filter " + sName);
            filterRegistry.remove(sName);
        }
        ZuulFilter filter = filterRegistry.get(sName);
        if (filter == null) {
            Class clazz = COMPILER.compile(file);
            /**
             * 非抽象类
             */
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                /**
                 * 实例化
                 * 默认 Class.newInstance()
                 */
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
                /**
                 * 写入缓存
                 */
                List<ZuulFilter> list = hashFiltersByType.get(filter.filterType());
                if (list != null) {
                    //rebuild this list
                    hashFiltersByType.remove(filter.filterType());
                }
                /**
                 * （针对脚本文件Groovy 特定的）
                 *记录文件绝对位置与文件修改时间
                 */
                filterRegistry.put(file.getAbsolutePath() + file.getName(), filter);
                filterClassLastModified.put(sName, file.lastModified());
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a list of filters by the filterType specified
     * 先从缓存中获取，若为空，从注册器中加载 并写入缓存
     *
     * @param filterType
     * @return a List<ZuulFilter>
     */
    public List<ZuulFilter> getFiltersByType(String filterType) {

        List<ZuulFilter> list = hashFiltersByType.get(filterType);
        if (list != null) return list;

        list = new ArrayList<ZuulFilter>();

        Collection<ZuulFilter> filters = filterRegistry.getAllFilters();
        for (Iterator<ZuulFilter> iterator = filters.iterator(); iterator.hasNext(); ) {
            ZuulFilter filter = iterator.next();
            if (filter.filterType().equals(filterType)) {
                list.add(filter);
            }
        }
        Collections.sort(list); // sort by priority

        hashFiltersByType.putIfAbsent(filterType, list);
        return list;
    }


    public static class TestZuulFilter extends ZuulFilter {

        public TestZuulFilter() {
            super();
        }

        @Override
        public String filterType() {
            return "test";
        }

        @Override
        public int filterOrder() {
            return 0;
        }

        @Override
        public boolean shouldFilter() {
            return false;
        }

        @Override
        public Object run() {
            return null;
        }
    }


    public static class UnitTest {

        @Mock
        File file;

        @Mock
        DynamicCodeCompiler compiler;

        @Mock
        FilterRegistry registry;

        FilterLoader loader;

        TestZuulFilter filter = new TestZuulFilter();

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);

            loader = spy(new FilterLoader());
            loader.setCompiler(compiler);
            loader.setFilterRegistry(registry);
        }

        @Test
        public void testGetFilterFromFile() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));
            verify(registry).put(any(String.class), any(ZuulFilter.class));
        }

        @Test
        public void testGetFiltersByType() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));

            verify(registry).put(any(String.class), any(ZuulFilter.class));

            final List<ZuulFilter> filters = new ArrayList<ZuulFilter>();
            filters.add(filter);
            when(registry.getAllFilters()).thenReturn(filters);

            List<ZuulFilter> list = loader.getFiltersByType("test");
            assertTrue(list != null);
            assertTrue(list.size() == 1);
            ZuulFilter filter = list.get(0);
            assertTrue(filter != null);
            assertTrue(filter.filterType().equals("test"));
        }


        @Test
        public void testGetFilterFromString() throws Exception {
            String string = "";
            doReturn(TestZuulFilter.class).when(compiler).compile(string, string);
            ZuulFilter filter = loader.getFilter(string, string);

            assertNotNull(filter);
            assertTrue(filter.getClass() == TestZuulFilter.class);
//            assertTrue(loader.filterInstanceMapSize() == 1);
        }


    }


}
