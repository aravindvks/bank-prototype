package bank.pbp.simulator.essence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the simulated core banking state in a JSON file ({@code sim.state-file}), so a restart
 * does not lose accounts that the bank's database still points at. No file configured: memory only.
 */
public final class SimulatorState {

    private static final Logger log = LoggerFactory.getLogger(SimulatorState.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Supplier<Map<String, Object>> snapshot;
    private String lastWritten = "";

    public SimulatorState(String stateFile, ObjectMapper mapper, Supplier<Map<String, Object>> snapshot,
                   Consumer<Map<String, Object>> restore) {
        this.file = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile);
        this.mapper = mapper;
        this.snapshot = snapshot;
        if (file == null) {
            return;
        }
        if (Files.exists(file)) {
            try {
                restore.accept(mapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
                }));
                log.info("sim.state.restored file={}", file);
            } catch (IOException | RuntimeException e) {
                log.warn("sim.state.unreadable file={} ({}); starting from the seed accounts", file, e.getMessage());
            }
        }
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-state");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::save, 2, 2, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::save, "sim-state-final"));
    }

    synchronized void save() {
        try {
            String json = mapper.writeValueAsString(snapshot.get());
            if (json.equals(lastWritten)) {
                return;
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastWritten = json;
        } catch (IOException | RuntimeException e) {
            log.warn("sim.state.not_saved file={} ({})", file, e.getMessage());
        }
    }
}
