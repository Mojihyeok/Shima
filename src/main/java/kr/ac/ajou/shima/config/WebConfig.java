package kr.ac.ajou.shima.config;

import kr.ac.ajou.shima.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 허용 오리진 패턴 — dev는 localhost + 사설망(폰/태블릿 테스트), prod는 환경변수로 교체 */
    private final String[] originPatterns;
    private final AuthInterceptor authInterceptor;

    public WebConfig(@Value("${shima.cors-origin-patterns}") String[] originPatterns,
                     AuthInterceptor authInterceptor) {
        this.originPatterns = originPatterns;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(originPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    /** 사장님 API만 로그인 필요. 손님 적립(/api/v1/c/**)·로그인·헬스체크는 공개 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/v1/owner/**");
    }
}
