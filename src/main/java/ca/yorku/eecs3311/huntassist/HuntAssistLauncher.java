package ca.yorku.eecs3311.huntassist;

import ca.yorku.eecs3311.huntassist.adapter.persistence.ProjectStoreLock;
import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.presentation.cli.HuntAssistCommand;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.Function;

@SpringBootApplication
public class HuntAssistLauncher {
    public static void main(String[] args) {
        int code = HuntAssistCommand.run(new HuntAssistLauncher(), new PrintWriter(System.out, true),
                new PrintWriter(System.err, true), args);
        if (code != 0) {
            System.exit(code);
        }
    }

    public <T> T runWithStore(Path directory, boolean web, int port,
                              Function<ConfigurableApplicationContext, T> workflow) {
        try (ProjectStoreLock lock = ProjectStoreLock.acquire(directory);
             ConfigurableApplicationContext context = start(lock.directory(), web, port)) {
            return workflow.apply(context);
        } catch (java.io.IOException e) {
            throw new AppException(AppException.Code.FAILED, "The local project store could not be closed cleanly.");
        }
    }

    private ConfigurableApplicationContext start(Path directory, boolean web, int port) {
        String jdbcUrl = "jdbc:h2:file:" + directory.resolve("huntassist").toString().replace(java.io.File.separatorChar, '/')
                + ";TRACE_LEVEL_FILE=0";
        return new SpringApplicationBuilder(HuntAssistLauncher.class)
                .web(web ? WebApplicationType.SERVLET : WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=sa",
                        "--spring.flyway.enabled=true",
                        "--server.address=127.0.0.1",
                        "--server.port=" + port,
                        "--logging.level.root=OFF");
    }
}
