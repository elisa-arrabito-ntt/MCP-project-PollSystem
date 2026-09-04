package com.example.pollSystem.mcp;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

@Component
public class PollMcpPrompts {

    @McpPrompt(
            name = "analyze_poll_results",
            description = "Quick analysis of a poll using direct database tools. Recommended when you don't want to attach resources."
    )
    public String analyzePollResults(
            @McpArg(name = "pollId", description = "The unique database ID of the target poll to analyze", required = true)
            String pollId) {

        return """
            You are tasked with analyzing Poll ID: %s.
            
            Please execute the following actions sequentially:
            1. Call the MCP tool `get_poll_details` with pollId="%s" to fetch the title, status, and options.
            2. Call the MCP tool `get_poll_votes` with pollId="%s" to get real-time vote metrics.
            3. Process the data: calculate absolute percentages for each option based on total votes. If total votes are 0, explicitly note it.
            
            Output Requirements:
            - Display the Poll Title and current Status.
            - Provide a clear breakdown of each option (Votes count and percentage).
            - Highlight the winning option (or declare a tie/no votes).
            """.formatted(pollId, pollId, pollId);
    }

    @McpPrompt(
            name = "analyze_poll_results_with_guide",
            description = "Advanced poll analysis guided by the official markdown guidelines and attached poll resources."
    )
    public String analyzePollResultsWithGuide(
            @McpArg(name = "pollId", description = "The unique database ID of the target poll to analyze", required = true)
            String pollId) {

        return """
            You are tasked with analyzing Poll ID: %s by following the official protocol.
            
            Context provided:
            - Instruction Guide resource: `poll://analysis-guide`
            - Target Poll Details resource: `poll://%s/details`
            
            Your execution path:
            1. Read and strictly adhere to the operational steps defined in `poll://analysis-guide`.
            2. Extract the structural poll data directly from `poll://%s/details`.
            3. Execute the MCP tool `get_poll_votes` with pollId="%s" to fetch the live, runtime metrics.
            4. Generate the final response using the exact layout, mathematical constraints, and output requirements specified inside the `poll://analysis-guide` document.
            """.formatted(pollId, pollId, pollId, pollId);
    }
}