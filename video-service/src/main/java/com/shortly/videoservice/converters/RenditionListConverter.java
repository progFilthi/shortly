package com.shortly.videoservice.converters;

import com.shortly.contracts.events.RenditionInfo;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/**
 * Stores the adaptive ladder as a JSON array on the video row.
 * <p>
 * Deliberately not a child table. The ladder is only ever read and written as a whole - there
 * is no query that needs "all videos with a 540p rendition" and no partial update - so a join
 * table would add a second entity, a second repository and a cascade for no query benefit. If
 * that query ever appears, this is the thing to replace.
 */
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
            // A row written by an older build, or hand-edited, must not make the whole
            // entity unreadable. An empty ladder degrades to "playback only"; a thrown
            // exception would take down the feed.
            return List.of();
        }
    }
}
