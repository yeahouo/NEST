package com.umi.nest.config;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用正确的 MIME 类型返回 PWA 的 manifest。
 *
 * <p><b>为什么需要这么一个只有一个方法的 Controller：</b>
 * Spring Boot 把 {@code .webmanifest} 当成未知扩展名，静态资源处理器一律以
 * {@code application/octet-stream} 返回。浏览器要求 manifest 是
 * {@code application/manifest+json}，类型不对就直接拒绝解析——而失败方式非常隐蔽：
 * 网页本身完全正常，只是「安装到主屏」的入口不出现，控制台只有一行语焉不详的报错。
 * 学校交付时这种问题最难排查，所以在此处一次性解决。
 *
 * <p><b>试过但走不通的两条路</b>（写下来免得后来人重试）：
 * <ul>
 *   <li>{@code spring.mvc.media-types.webmanifest=...}——这个属性配的是内容协商
 *       （Controller 按扩展名挑返回格式），根本不经过静态资源处理器，改了没有任何效果。
 *   <li>{@code MediaTypeFactory.addFileExtension(...)}——Spring Framework 6.1.4 里
 *       这个静态方法还不存在（编译期「找不到符号」）。
 * </ul>
 *
 * <p>{@code @GetMapping} 的优先级高于静态资源处理器
 * （{@code RequestMappingHandlerMapping} 的 order 是 0，资源映射被放在最低优先级），
 * 所以这个映射能稳定接管 {@code /manifest.webmanifest}，其余静态资源不受影响。
 */
@RestController
public class WebManifestController {

    /** 与 static/ 下真实的文件名保持一致；改名字时这里和三个页面的 link 要一起改。 */
    private static final String RESOURCE = "static/manifest.webmanifest";

    private static final MediaType MANIFEST_JSON =
            MediaType.parseMediaType("application/manifest+json");

    /**
     * 每次请求读一次文件。
     *
     * <p>manifest 只有 1KB 出头、又只在装 App 时被取一次，为它加一层缓存
     * 换来的复杂度不划算；反而在开发期每次改完文件刷新就能看到，省掉「改了没生效」的怀疑。
     * 真正需要省的是浏览器那一侧，所以下面给了一小时的 Cache-Control。
     */
    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json")
    public ResponseEntity<String> manifest() throws Exception {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        String body = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MANIFEST_JSON)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .body(body);
    }
}
