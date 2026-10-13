package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotNull;
import kr.ac.ajou.shima.domain.UiMode;

public record UiModeRequest(@NotNull UiMode uiMode) {}
