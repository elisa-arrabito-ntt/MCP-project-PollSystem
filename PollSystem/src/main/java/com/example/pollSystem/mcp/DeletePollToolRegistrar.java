package com.example.pollSystem.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registra manualmente su {@link McpSyncServer} il tool MCP {@code request_delete_poll},
 * bypassando la registrazione automatica basata sull'annotazione {@code @McpTool}.
 * <p>
 * Motivo: {@code spring-ai-mcp-annotations} (verificato nella versione 2.0.0, non configurabile
 * tramite l'annotazione stessa) cattura sempre qualunque eccezione lanciata da un metodo
 * {@code @McpTool} e la converte in un {@link McpSchema.CallToolResult} con {@code isError=true},
 * mai in un vero errore JSON-RPC a livello di protocollo. Per rispettare la specifica MCP e
 * restituire lo standard JSON-RPC error {@code -32042} ({@code URL_ELICITATION_REQUIRED}), la
 * {@link McpError} lanciata da {@link PollMcpTools#requestDeletePollLogic} deve propagare
 * inalterata fuori dal callHandler del tool: questo è possibile solo registrando il tool con un
 * callHandler scritto a mano (nessuna invocazione riflessiva di mezzo), come fatto qui.
 * <p>
 * Implementato come {@link ApplicationRunner} così da eseguire dopo che il contesto Spring (e
 * quindi il bean {@link McpSyncServer}, costruito dall'auto-configurazione MCP raccogliendo tutti
 * i metodi {@code @McpTool}/{@code @McpPrompt}/{@code @McpResource} esistenti a quel momento) è
 * completamente inizializzato.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletePollToolRegistrar implements ApplicationRunner {

    private final PollMcpTools pollMcpTools;
    private final ObjectProvider<McpSyncServer> mcpSyncServerProvider;

    @Override
    public void run(ApplicationArguments args) {
        McpSyncServer mcpSyncServer = mcpSyncServerProvider.getIfAvailable();
        if (mcpSyncServer == null) {
            log.warn("McpSyncServer bean not available: 'request_delete_poll' tool was not registered.");
            return;
        }

        mcpSyncServer.addTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildToolDefinition())
                .callHandler(this::handleRequestDeletePoll)
                .build());

        log.info("Manually registered MCP tool 'request_delete_poll' (bypassing @McpTool) " +
                "so that URL_ELICITATION_REQUIRED (-32042) can propagate as a real JSON-RPC error.");
    }

    private McpSchema.Tool buildToolDefinition() {
        Map<String, Object> pollIdProperty = Map.of(
                "type", "integer",
                "description", "The unique database ID of the poll to delete"
        );
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("pollId", pollIdProperty),
                "required", List.of("pollId")
        );

        return McpSchema.Tool.builder("request_delete_poll")
                .description("Initiate the asynchronous deletion of a poll. If no confirmation is already " +
                        "pending for this poll, responds with a JSON-RPC 'URL elicitation required' error " +
                        "(-32042) containing the confirmation URL: open it in a browser to confirm. If a " +
                        "confirmation is already pending, returns its current status directly instead of " +
                        "creating a new one, so retrying this same tool call is always safe.")
                .inputSchema(inputSchema)
                .build();
    }

    private McpSchema.CallToolResult handleRequestDeletePoll(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {

        Long pollId = toLong(request.arguments() != null ? request.arguments().get("pollId") : null);
        if (pollId == null) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Missing or invalid required parameter 'pollId'.")
                    .build();
        }

        try {
            String result = pollMcpTools.requestDeletePollLogic(pollId, exchange.sessionId());
            return McpSchema.CallToolResult.builder().addTextContent(result).build();
        } catch (McpError mcpError) {
            // Deve propagare inalterata: diventerà il vero errore JSON-RPC -32042 della risposta a
            // tools/call (vedi McpServerSession, che usa mcpError.getJsonRpcError() quando l'eccezione
            // propagata da un request handler è un'istanza di McpError).
            throw mcpError;
        } catch (Exception e) {
            // Qualunque altra eccezione di business (autorizzazione, poll non trovato, ecc.) resta
            // un errore a livello di tool result, coerentemente con il comportamento degli altri tool
            // annotati con @McpTool.
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(e.getMessage())
                    .build();
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
