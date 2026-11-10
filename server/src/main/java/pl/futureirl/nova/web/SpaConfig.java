package pl.futureirl.nova.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the statically exported Next.js client from classpath:/static. A route like /rozmowa maps to
 * /rozmowa/index.html; unknown non-file routes fall back to the root page.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_next/static/**")
                .addResourceLocations("classpath:/static/_next/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        if (path.startsWith("api/") || path.startsWith("actuator/")) {
                            return null;
                        }
                        var direct = super.getResource(path, location);
                        if (direct != null) {
                            return direct;
                        }
                        var lastSegment = path.substring(path.lastIndexOf('/') + 1);
                        if (lastSegment.contains(".")) {
                            return null; // a missing file stays a 404
                        }
                        var clean = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                        var page = clean.isEmpty() ? null : super.getResource(clean + "/index.html", location);
                        if (page != null) {
                            return page;
                        }
                        var index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
