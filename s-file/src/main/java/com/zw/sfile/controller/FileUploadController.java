package com.zw.sfile.controller;

import com.zw.scommon.api.*;
import com.zw.sfile.service.FileService;
import com.zw.sfile.service.impl.FileServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    @Autowired
    private FileService fileService;

    /**
     * 图片上传接口
     * POST /file/upload
     */
    @PostMapping("/upload")
    public CommonResult<String> upload(@RequestParam("file") MultipartFile file) {
        // 调用 Service 处理核心逻辑
        String imageUrl = fileService.uploadImage(file);

        // 成功后，将生成的静态 URL 返回给前端
        return CommonResult.success(imageUrl);
    }

    @GetMapping("/{fileName}")
    public ResponseEntity<Resource> getFile(@PathVariable String fileName) {
        try {
            Path uploadDir = FileServiceImpl.getUploadDir();
            Path target = uploadDir.resolve(fileName).normalize();
            if (!target.startsWith(uploadDir)) {
                return ResponseEntity.badRequest().build();
            }

            File file = target.toFile();
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);

            // 根据扩展名动态判断 Content-Type
            String contentType = "application/octet-stream";
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (ext) {
                case "jpg": case "jpeg": contentType = "image/jpeg"; break;
                case "png": contentType = "image/png"; break;
                case "webp": contentType = "image/webp"; break;
                case "gif": contentType = "image/gif"; break;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
