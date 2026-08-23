/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.nio.file.Paths;

/**
 * 自动装配核心服务并注册上传/下载 Servlet。
 *
 * <p>所有组件均可通过自定义 Bean 覆盖（{@code @ConditionalOnMissingBean}）。</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"javax.servlet.Servlet", "javax.servlet.MultipartConfigElement"})
@EnableConfigurationProperties(UploadFileProperties.class)
public class UploadFileAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TaskStore uploadFileTaskStore(UploadFileProperties properties) {
        if (properties.getMetadataDir() != null && !properties.getMetadataDir().trim().isEmpty()) {
            return new FileTaskStore(properties.getMetadataDir());
        }
        return new MemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkStorage uploadFileChunkStorage(UploadFileProperties properties) {
        return new LocalFileChunkStorage(Paths.get(properties.getStorageDir(), "chunks"));
    }

    @Bean
    @ConditionalOnMissingBean
    public ResumableUploadService resumableUploadService(TaskStore taskStore,
                                                         ChunkStorage chunkStorage,
                                                         UploadFileProperties properties) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        return new ResumableUploadService(taskStore, chunkStorage, mergedDir, properties.isVerifyChecksum());
    }

    @Bean
    @ConditionalOnMissingBean
    public ResumableDownloadService resumableDownloadService(TaskStore taskStore,
                                                             UploadFileProperties properties) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        return new ResumableDownloadService(taskStore, mergedDir);
    }

    @Bean
    public ServletRegistrationBean<UploadServlet> uploadFileServletRegistration(
            ResumableUploadService uploadService, UploadFileProperties properties) {
        UploadServlet servlet = new UploadServlet();
        servlet.setUploadService(uploadService);
        ServletRegistrationBean<UploadServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getUploadUrl());
        registration.setName("uploadFileServlet");
        registration.setLoadOnStartup(1);
        registration.setMultipartConfig(new MultipartConfigElement(
                null, properties.getMaxChunkSize(), properties.getMaxRequestSize(), 1024 * 1024));
        return registration;
    }

    @Bean
    public ServletRegistrationBean<DownloadServlet> downloadFileServletRegistration(
            ResumableDownloadService downloadService, UploadFileProperties properties) {
        DownloadServlet servlet = new DownloadServlet();
        servlet.setDownloadService(downloadService);
        ServletRegistrationBean<DownloadServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getDownloadUrl());
        registration.setName("downloadFileServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
