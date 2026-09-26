package com.shortly.videoservice.converters;

import com.shortly.contracts.events.RenditionInfo;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/** Stores the adaptive ladder as a JSON array on the video row. Deliberately not a child table. */
@Converter
public class RenditionListConverter implements AttributeConverter<List<RenditionInfo>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RenditionInfo>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<RenditionInfo> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<RenditionInfo> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (RuntimeException e) {
            // A row written by an older build, or hand-edited, must not make the whole entity unreadable. An
            // empty ladder degrades to "playback only";
            return List.of();
        }
    }
}
