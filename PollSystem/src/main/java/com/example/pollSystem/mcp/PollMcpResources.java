package com.example.pollSystem.mcp;

import com.example.pollSystem.service.PollService;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class PollMcpResources {

    private final PollService pollService;
    private final ObjectMapper objectMapper;

    public PollMcpResources(PollService pollService, ObjectMapper objectMapper) {
        this.pollService = pollService;
        this.objectMapper = objectMapper;
    }

    @McpResource(
            uri = "poll://{id}",
            name = "poll-basic-details",
            title = "Poll basic details",
            description = "Provides core metadata of a specific poll (title, creation date, status) directly from the application context.",
            mimeType = "application/json"
    )
    public String pollById(@McpArg(name = "id", description = "The unique database identifier of the poll") String id) throws Exception {
        Long pollId = Long.parseLong(id);
        return objectMapper.writeValueAsString(pollService.getPollById(pollId));
    }

    @McpResource(
            uri = "poll://{id}/details",
            name = "poll-full-details",
            title = "Poll full details",
            description = "Provides the complete structural dataset of a poll, including all available response options, retrieved from the database.",
            mimeType = "application/json"
    )
    public String pollDetails(@McpArg(name = "id", description = "The unique database identifier of the poll") String id) throws Exception {
        Long pollId = Long.parseLong(id);
        return objectMapper.writeValueAsString(pollService.getPollDetails(pollId));
    }


    @McpResource(
            uri = "poll://analysis-guide",
            name = "poll-analysis-guide",
            title = "Poll analysis guide",
            description = "Shared markdown instructions for poll analysis.",
            mimeType = "text/markdown"
    )
    public String pollAnalysisGuide() throws Exception {
        var resource = new ClassPathResource("/mcp/poll-analysis-guide.md");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }


}