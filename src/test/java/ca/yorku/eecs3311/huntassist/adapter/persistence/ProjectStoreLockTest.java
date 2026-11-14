package ca.yorku.eecs3311.huntassist.adapter.persistence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ProjectStoreLockTest {
    @TempDir Path temp;

    @Test
    void detectsSecondOwnerAndPermitsReopenAfterClosing() throws Exception {
        Path store = temp.resolve("data");
        try (var owner = ProjectStoreLock.acquire(store)) {
            assertThat(owner.directory()).isEqualTo(store.toRealPath());
            assertThatExceptionOfType(AppException.class).isThrownBy(() -> ProjectStoreLock.acquire(store))
                    .satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(AppException.Code.CONFLICT);
                        assertThat(failure.getMessage()).contains(owner.directory().toString());
                        assertThat(failure.getMessage()).contains("Close the HuntAssist server");
                    });
        }
        try (var next = ProjectStoreLock.acquire(store)) {
            assertThat(next.directory()).isEqualTo(store.toRealPath());
        }
    }

    @Test
    void rejectsNonFileDatabaseBeforeStartup() throws Exception {
        Files.createDirectory(temp.resolve("huntassist.mv.db"));
        assertThatExceptionOfType(AppException.class).isThrownBy(() -> ProjectStoreLock.acquire(temp))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.REFUSED));
    }
}
