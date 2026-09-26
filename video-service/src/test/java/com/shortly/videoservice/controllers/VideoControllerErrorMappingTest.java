package com.shortly.videoservice.controllers;

import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.enums.VideoStatus;
import com.shortly.videoservice.exceptions.ThumbnailUnavailableException;
import com.shortly.videoservice.exceptions.GlobalExceptionHandler;
import com.shortly.videoservice.security.CallerIdentity;
import com.shortly.videoservice.security.CallerIdentityArgumentResolver;
import com.shortly.videoservice.exceptions.VideoNotAuthorizedException;
import com.shortly.videoservice.exceptions.VideoNotFoundException;
import com.shortly.videoservice.exceptions.VideoNotReadyException;
import com.shortly.videoservice.services.UploadVerificationException;
import com.shortly.videoservice.services.VideoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that domain failures become the right HTTP status with the right machine-readable
 * code.
 * <p>
 * This matters more than it looks. Without the advice, an oversized upload surfaces as a 500
 * and the mobile client treats a permanent, user-fixable rejection as a transient server error
 * and retries it forever. These assertions pin the mapping.
 * <p>
 * A {@code @WebMvcTest} slice rather than a full context load: this service owns a Postgres
 * schema and a broker connection, and a context-load test would need real infrastructure to
 * assert almost nothing. The end-to-end behaviour is covered by scripts/e2e-pipeline-test.sh.
 */
@WebMvcTest(VideoController.class)
@Import({GlobalExceptionHandler.class, CallerIdentityArgumentResolver.class})
class VideoControllerErrorMappingTest {

    private static final String USER = "user-1";
    private static final String OTHER_USER = "user-2";
    private static final UUID VIDEO_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoService videoService;

    /**
     * Seeds the attribute the gateway filter would normally set.
     * <p>
     * The identity is no longer read from a header, so tests supply it the way production does -
     * as a request attribute. That keeps the test honest about the trust boundary.
     */
    private MockHttpServletRequestBuilder asCaller(MockHttpServletRequestBuilder builder,
                                                    String userId) {
        return builder.requestAttr(CallerIdentity.REQUEST_ATTRIBUTE,
                new CallerIdentity(userId, "tester", "tester@example.com"));
    }

    /* ------------------------------- happy paths ------------------------------- */

    @Test
    void createVideoReturns201WithTheUploadLimits() throws Exception {
        when(videoService.createVideo(any(), eq(USER))).thenReturn(new CreateS3PresignedUrlResponse(
                VIDEO_ID, "https://s3.example/presigned", "raw/user-1/x.mp4",
                Instant.now().plusSeconds(900), 524_288_000L, 60, true));

        mockMvc.perform(asCaller(post("/api/v1/videos"), USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"a clip","description":"d","contentType":"video/mp4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoId").value(VIDEO_ID.toString()))
                // The client needs these to pre-flight the upload instead of discovering the
                // limits after spending the bandwidth.
                .andExpect(jsonPath("$.maxBytes").value(524288000L))
                .andExpect(jsonPath("$.maxDurationSeconds").value(60))
                .andExpect(jsonPath("$.requiresH264").value(true));
    }

    @Test
    void completeReturns202Not200() throws Exception {
        // 200 would tell the client the video is ready to play. It is not: it is queued.
        when(videoService.confirmUploadComplete(VIDEO_ID, USER))
                .thenReturn(response(VideoStatus.PROCESSING));

        mockMvc.perform(asCaller(post("/api/v1/videos/{id}/complete", VIDEO_ID), USER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.videoStatus").value("PROCESSING"));
    }

    /* ----------------------------- validation errors ---------------------------- */

    @Test
    void rejectsANonVideoContentTypeWith400AndAViolationList() throws Exception {
        mockMvc.perform(post("/api/v1/videos")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"a clip","contentType":"text/html"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void rejectsABlankTitleWith400() throws Exception {
        mockMvc.perform(asCaller(post("/api/v1/videos"), USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","contentType":"video/mp4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    /* ------------------------- domain failure -> status -------------------------- */

    @Test
    void oversizedUploadIs413AndCarriesBothSizes() throws Exception {
        when(videoService.confirmUploadComplete(VIDEO_ID, USER)).thenThrow(
                new UploadVerificationException(UploadVerificationException.Kind.TOO_LARGE,
                        "Uploaded object is 612000000 bytes, the limit is 524288000",
                        612_000_000L, 524_288_000L));

        mockMvc.perform(asCaller(post("/api/v1/videos/{id}/complete", VIDEO_ID), USER))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("upload-too-large"))
                // The client needs the numbers to tell the user what to do about it.
                .andExpect(jsonPath("$.actualBytes").value(612000000L))
                .andExpect(jsonPath("$.maxBytes").value(524288000L));
    }

    @Test
    void missingUploadIs409Not500() throws Exception {
        when(videoService.confirmUploadComplete(VIDEO_ID, USER)).thenThrow(
                new UploadVerificationException(UploadVerificationException.Kind.MISSING,
                        "No object was uploaded", 0, 524_288_000L));

        mockMvc.perform(asCaller(post("/api/v1/videos/{id}/complete", VIDEO_ID), USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("upload-missing"));
    }

    @Test
    void unknownVideoIs404() throws Exception {
        when(videoService.getVideoById(VIDEO_ID)).thenThrow(new VideoNotFoundException(VIDEO_ID));

        mockMvc.perform(get("/api/v1/videos/{id}", VIDEO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void aNonOwnerIs403() throws Exception {
        when(videoService.getVideoById(VIDEO_ID))
                .thenThrow(new VideoNotAuthorizedException(VIDEO_ID));

        mockMvc.perform(get("/api/v1/videos/{id}", VIDEO_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access-denied"));
    }

    @Test
    void selectingACoverBeforeProcessingIs409AndReportsTheStatus() throws Exception {
        when(videoService.selectThumbnail(eq(VIDEO_ID), any(), eq(USER)))
                .thenThrow(new VideoNotReadyException(VIDEO_ID, VideoStatus.PROCESSING));

        mockMvc.perform(asCaller(put("/api/v1/videos/{id}/thumbnail", VIDEO_ID), USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tileIndex":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflicting-state"))
                // Lets the client distinguish "still working" from "will never work" without
                // parsing the message.
                .andExpect(jsonPath("$.currentStatus").value("PROCESSING"));
    }

    @Test
    void selectingACoverWithNoSpriteSheetIs422() throws Exception {
        when(videoService.selectThumbnail(eq(VIDEO_ID), any(), eq(USER)))
                .thenThrow(new ThumbnailUnavailableException(VIDEO_ID));

        mockMvc.perform(asCaller(put("/api/v1/videos/{id}/thumbnail", VIDEO_ID), USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tileIndex":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unprocessable"));
    }

    @Test
    void aNegativeTileIndexIsRejected() throws Exception {
        // An index, not a URL, is what the client sends. Accepting a negative one would let it
        // address memory outside the sheet.
        mockMvc.perform(asCaller(put("/api/v1/videos/{id}/thumbnail", VIDEO_ID), USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tileIndex":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    void aMutationWithoutAGatewayIdentityIsRejected() throws Exception {
        // Every endpoint that writes needs a caller the gateway asserted. Without one there is no
        // owner to attribute the video to, and defaulting to "anonymous" would silently create
        // unattributable content. Reaches the handler as a 403, not a 500, because it is a
        // routing failure rather than a fault.
        mockMvc.perform(post("/api/v1/videos/{id}/complete", VIDEO_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("gateway-secret-invalid"));
    }

    @Test
    void readsArePublicAndDoNotRequireAUserHeader() throws Exception {
        when(videoService.getVideoById(VIDEO_ID)).thenReturn(response(VideoStatus.READY));

        mockMvc.perform(get("/api/v1/videos/{id}", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoStatus").value("READY"));
    }

    private VideoResponse response(VideoStatus status) {
        return new VideoResponse(VIDEO_ID, "t", "d", null, null, null, null, null, null, null,
                null, USER, status, null, null, null, List.of());
    }
}
