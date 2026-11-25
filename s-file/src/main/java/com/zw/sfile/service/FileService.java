package com.zw.sfile.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileService {
    /**
     * 上传图片并返回可访问的绝对 URL
     */
    String uploadImage(MultipartFile file);
}