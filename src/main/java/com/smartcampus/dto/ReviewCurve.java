package com.smartcampus.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReviewCurve {
    private List<ReviewPoint> points;
    private String recommendation;
}