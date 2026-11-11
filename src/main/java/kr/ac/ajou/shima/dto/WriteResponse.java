package kr.ac.ajou.shima.dto;

import java.util.List;

/** 완성된 글 — 인스타는 title 없음. source: AI | RULE(기본 문구) */
public record WriteResponse(String channel, String topic, String title, String body, List<String> hashtags, String source) {}
