package ca.yorku.eecs3311.huntassist.presentation.cli;

import ca.yorku.eecs3311.huntassist.HuntAssistLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HuntAssistCommandTest {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void helpAndSyntaxErrorsDoNotOpenTheStoreOrEchoArguments() {
        assertThat(run("--help").out).contains("Usage:", "serve", "traffic", "plan");
        Result invalid = run("surface", "list", "--project", "untrusted-value");
        assertThat(invalid.code).isEqualTo(2);
        assertThat(invalid.err).contains("Invalid command").doesNotContain("untrusted-value");
        assertThat(Files.exists(temporary.resolve("app"))).isFalse();
    }

    @Test
    void writesAProjectToTheSharedStoreAndReadsItFromAnotherCliInvocation() {
        Result created = run("project", "create", "--name", "Course demo", "--include", "*.example.test",
                "--exclude", "private.example.test", "--format", "json");
        assertThat(created.code).as(created.err).isZero();
        assertThat(created.err).isEmpty();
        var creation = json.readTree(created.out);
        assertThat(creation.path("schemaVersion").asInt()).isEqualTo(1);
        String id = creation.path("data").path("id").asString();
        assertThat(id).isNotEmpty();

        Result decision = run("--format", "json", "project", "scope", "--project", id, "private.example.test");
        assertThat(decision.code).as(decision.err).isZero();
        assertThat(json.readTree(decision.out).path("data").asString()).isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    void reportsUnknownProjectsWithStructuredExitThree() {
        Result result = run("--format", "json", "traffic", "history",
                "--project", "591a65a4-8220-48d9-aa35-19b390f40625");
        assertThat(result.code).as(result.err).isEqualTo(3);
        var response = json.readTree(result.out);
        assertThat(response.path("status").asString()).isEqualTo("error");
        assertThat(response.path("errors").get(0).path("code").asInt()).isEqualTo(3);
    }

    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String[] withStore = new String[arguments.length + 2];
        withStore[0] = "--store";
        withStore[1] = temporary.resolve("app").toString();
        System.arraycopy(arguments, 0, withStore, 2, arguments.length);
        int code = HuntAssistCommand.run(new HuntAssistLauncher(), new PrintWriter(out), new PrintWriter(err), withStore);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
