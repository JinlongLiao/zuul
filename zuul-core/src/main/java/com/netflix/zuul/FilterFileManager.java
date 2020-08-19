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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.*;

/**
 * This class manages the directory polling for changes and new Groovy filters.
 * Polling interval and directories are specified in the initialization of the class, and a poller will check
 * for changes and additions.
 * <p>
 * 此类管理目录轮询以查找更改和新的Groovy过滤器。
 * 轮询间隔和目录是在类的初始化中指定的，轮询器将检查
 * 是否有更改和添加。
 *
 * @author Mikey Cohen
 * Date: 12/7/11
 * Time: 12:09 PM
 */
public class FilterFileManager {

    private static final Logger LOG = LoggerFactory.getLogger(FilterFileManager.class);

    String[] aDirectories;
    int pollingIntervalSeconds;
    Thread poller;
    boolean bRunning = true;

    static FilenameFilter FILENAME_FILTER;

    static FilterFileManager INSTANCE;
    static final FileFilter filter = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };

    private FilterFileManager() {
    }

    public static void setFilenameFilter(FilenameFilter filter) {
        FILENAME_FILTER = filter;
    }

    /**
     * Initialized the GroovyFileManager.
     *
     * @param pollingIntervalSeconds the polling interval in Seconds 轮训时间（S）
     * @param directories            Any number of paths to directories to be polled may be specified Groovy 过滤器路径
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public static void init(int pollingIntervalSeconds, String... directories) throws Exception, IllegalAccessException, InstantiationException {
        if (INSTANCE == null) {
            INSTANCE = new FilterFileManager();
        }

        INSTANCE.aDirectories = directories;
        INSTANCE.pollingIntervalSeconds = pollingIntervalSeconds;
        /**
         * 读取文件
         */
        INSTANCE.manageFiles();
        /**
         * 开启新线程 定时查询
         */
        INSTANCE.startPoller();

    }

    public static FilterFileManager getInstance() {
        return INSTANCE;
    }

    /**
     * Shuts down the poller
     */
    public static void shutdown() {
        INSTANCE.stopPoller();
    }


    void stopPoller() {
        bRunning = false;
    }

    void startPoller() {
        poller = new Thread("GroovyFilterFileManagerPoller") {
            @Override
            public void run() {
                while (bRunning) {
                    try {
                        sleep(pollingIntervalSeconds * 1000);
                        manageFiles();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        poller.setDaemon(true);
        poller.start();
    }

    /**
     * Returns the directory File for a path. A Runtime Exception is thrown if the directory is in valid
     *
     * @param sPath
     * @return a File representing the directory path
     */
    public File getDirectory(String sPath) {
        File directory = new File(sPath);
        if (!directory.isDirectory()) {
            URL resource = FilterFileManager.class.getClassLoader().getResource(sPath);
            try {
                directory = new File(resource.toURI());
            } catch (Exception e) {
                LOG.error("Error accessing directory in classloader. path=" + sPath, e);
            }
            if (!directory.isDirectory()) {
                throw new RuntimeException(directory.getAbsolutePath() + " is not a valid directory");
            }
        }
        return directory;
    }

    /**
     * Returns a List<File> of all Files from all polled directories
     * 存在 文件夹下不能包涵子文件夹 问题
     *
     * @return
     */
    List<File> getFiles() {
        List<File> list = new ArrayList<File>();
        for (String sDirectory : aDirectories) {
            if (sDirectory != null) {
                File directory = getDirectory(sDirectory);
//                File[] aFiles = directory.listFiles(FILENAME_FILTER);
                List<File> aFiles = listFiles(FILENAME_FILTER, directory);
                if (aFiles != null) {
                    list.addAll(aFiles);
                }
            }
        }
        return list;
    }

    /**
     * 得到所有的文件下的 符合要求的文件
     *
     * @param filenameFilter
     * @param directory
     * @return List<File>
     */
    private List<File> listFiles(FilenameFilter filenameFilter, File directory) {
        List<File> fileList = new ArrayList<>(Arrays.asList(directory.listFiles(filenameFilter)));

        final File[] files = directory.listFiles(filter);
        if (files != null && files.length > 0) {
            for (File file : files) {
                fileList.addAll(this.listFiles(filenameFilter, file));
            }
        }
        return fileList;
    }

    /**
     * puts files into the FilterLoader. The FilterLoader will only addd new or changed filters
     * <p>
     * 将文件放入FilterLoader。 FilterLoader将仅添加新的或更改的过滤器
     *
     * @param aFiles a List<File>
     * @throws IOException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    void processGroovyFiles(List<File> aFiles) throws Exception, InstantiationException, IllegalAccessException {

        for (File file : aFiles) {
            FilterLoader.getInstance().putFilter(file);
        }
    }

    void manageFiles() throws Exception, IllegalAccessException, InstantiationException {
        List<File> aFiles = getFiles();
        processGroovyFiles(aFiles);
    }


    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {
        @Mock
        private File nonGroovyFile;
        @Mock
        private File groovyFile;

        @Mock
        private File directory;

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);
        }


        @Test
        public void testFileManagerInit() throws Exception, InstantiationException, IllegalAccessException {
            FilterFileManager manager = new FilterFileManager();

            manager = spy(manager);
            manager.INSTANCE = manager;
            doNothing().when(manager.INSTANCE).manageFiles();
            manager.init(1, "test", "test1");
            verify(manager, atLeast(1)).manageFiles();
            verify(manager, times(1)).startPoller();
            assertNotNull(manager.poller);

        }

    }

}
