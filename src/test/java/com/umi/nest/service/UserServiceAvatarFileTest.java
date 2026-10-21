package com.umi.nest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 头像文件名白名单（安全边界）单测。
 *
 * <p>背景：{@code GET /api/user/avatar/file/{filename}} 曾经把 filename 直接拼到
 * 上传目录上，Windows 下 {@code ..\} 与 {@code ../} 都是分隔符，能读到上传目录外的
 * 任意文件（含 application.properties）。这里把「路径类输入」逐条钉死。
 */
@DisplayName("UserService - 头像文件名白名单")
class UserServiceAvatarFileTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService();
        ReflectionTestUtils.setField(userService, "uploadDir", System.getProperty("java.io.tmpdir") + "/nest-avatar-test");
    }

    @ParameterizedTest(name = "拒绝 [{0}]")
    @ValueSource(strings = {
            "..\\..\\application.properties",
            "../application.properties",
            "../../etc/passwd",
            "..",
            "1_x/../../y.png",
            "C:\\Windows\\win.ini",
            "sub/dir.png",
            ".",
            " leading.png"
    })
    @DisplayName("路径类输入一律 403")
    void rejectsPathLikeNames(String filename) {
        ResponseEntity<?> resp = userService.loadAvatarFile(filename);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    @DisplayName("null / 空串 - 403")
    void rejectsNullAndEmpty() {
        assertEquals(HttpStatus.FORBIDDEN, userService.loadAvatarFile(null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, userService.loadAvatarFile("").getStatusCode());
    }

    @Test
    @DisplayName("超长文件名（>128 字符）- 403")
    void rejectsOverlongName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        sb.append(".png");
        assertEquals(HttpStatus.FORBIDDEN, userService.loadAvatarFile(sb.toString()).getStatusCode());
    }

    @Test
    @DisplayName("合法文件名 - 不因白名单被拒（目录不存在时回默认头像）")
    void acceptsLegalName() {
        ResponseEntity<?> resp = userService.loadAvatarFile("1_1678888888888.png");
        assertNotNull(resp);
        // 上传目录里没有这个文件 ⇒ 走默认头像分支，200 + SVG，绝不是 403
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
