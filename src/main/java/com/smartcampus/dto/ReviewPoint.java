package com.smartcampus.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReviewPoint {
    private int day;
    private LocalDateTime reviewTime;
    private String description;
}