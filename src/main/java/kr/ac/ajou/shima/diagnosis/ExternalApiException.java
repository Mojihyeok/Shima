package kr.ac.ajou.shima.diagnosis;

/** 외부 API(네이버) 호출 실패 — configured=false면 키 미설정 */
public class ExternalApiException extends RuntimeException {
    private final boolean configured;

    public ExternalApiException(String message, boolean configured) {
        super(message);
        this.configured = configured;
    }

    public boolean isConfigured() { return configured; }
}
