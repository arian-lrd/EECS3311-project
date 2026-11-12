package ca.yorku.eecs3311.huntassist.domain.project;

import org.junit.jupiter.api.Test;

import java.util.List;

import static ca.yorku.eecs3311.huntassist.domain.project.ProgramScope.Decision.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProgramScopeTest {
    private final ProgramScope scope = new ProgramScope(
            List.of(" *.example.test ", "other.test"), List.of("private.example.test"));

    @Test
    void wildcardCoversSubdomainsButNotApexOrSuffixLookalikes() {
        assertThat(scope.classify("API.EXAMPLE.TEST.")).isEqualTo(IN_SCOPE);
        assertThat(scope.classify("one.two.example.test")).isEqualTo(IN_SCOPE);
        assertThat(scope.classify("example.test")).isEqualTo(OUT_OF_SCOPE);
        assertThat(scope.classify("wrongexample.test")).isEqualTo(OUT_OF_SCOPE);
    }

    @Test
    void exclusionOverridesInclusionAndAmbiguousHostIsUnresolved() {
        assertThat(scope.classify("private.example.test")).isEqualTo(OUT_OF_SCOPE);
        assertThat(scope.classify("https://api.example.test")).isEqualTo(UNRESOLVED);
        assertThat(scope.classify("api.example.test:443")).isEqualTo(UNRESOLVED);
        assertThat(scope.classify(null)).isEqualTo(UNRESOLVED);
    }

    @Test
    void rejectsEmptyOrMalformedScope() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramScope(List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramScope(List.of("ex*ample.test"), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramScope(List.of("*.test"), List.of()));
    }
}
