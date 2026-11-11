package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SNS 글 대신 쓰기 — channel: instagram | blog, topic: WriterService.TOPICS, note: 사장님 한 줄 메모(선택) */
public record WriteRequest(@NotBlank String channel, @NotBlank String topic, @Size(max = 200) String note) {}
