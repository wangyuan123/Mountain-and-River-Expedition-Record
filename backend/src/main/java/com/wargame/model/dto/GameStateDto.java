package com.wargame.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO representing the complete game state response.
 * Wraps a Map that matches the JS state structure for frontend compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameStateDto {

    private Map<String, Object> state;
}
