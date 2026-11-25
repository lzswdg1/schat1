package com.zw.sfile.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.zw.scommon.exception.ApiException;
import com.zw.sfile.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class FileServiceImpl implements FileService {

    // 允许上传的图片白名单
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "webp");

    // 上传目录：优先读环境变量 UPLOAD_DIR，本地开发回退到项目根目录下的 schat-uploads
    private static final Path UPLOAD_DIR = Paths.get(
            System.getenv("UPLOAD_DIR") != null
                    ? System.getenv("UPLOAD_DIR")
                    : System.getProperty("user.dir") + File.separator + "schat-uploads"
    ).toAbsolutePath().normalize();

    public static Path getUploadDir() {
        return UPLOAD_DIR;
    }

    @Override
    public String uploadImage(MultipartFile file) {
        // 1. ：只有登录用户才能传图！(Sa-Token 自动校验，未登录会抛出异常)
        StpUtil.checkLogin();

        // 2. 空文件校验
        if (file.isEmpty()) {
            throw new ApiException("上传文件不能为空");
        }

        // 3. 提取原文件名与后缀，防篡改校验
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new ApiException("文件格式不合法");
        }

        String ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException("不支持的文件类型，仅支持: " + ALLOWED_EXTENSIONS);
        }

        // 4. 生成防路径穿透的绝对唯一文件名 (UUID)
        String newFileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            java.nio.file.Files.createDirectories(UPLOAD_DIR);
            Path dest = UPLOAD_DIR.resolve(newFileName).normalize();
            if (!dest.startsWith(UPLOAD_DIR)) {
                throw new ApiException("文件路径不合法");
            }
            file.transferTo(dest.toFile());
        } catch (IOException e) {
            throw new ApiException("文件物理落盘失败", e);
        }

        return "/file/" + newFileName;
    }
}
