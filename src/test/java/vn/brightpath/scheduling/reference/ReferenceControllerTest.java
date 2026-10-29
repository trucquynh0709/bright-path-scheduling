package vn.brightpath.scheduling.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import vn.brightpath.scheduling.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReferenceControllerTest {

    @Autowired
    ReferenceController controller;

    @Test
    void listsSeedDataAndSimulatedNow() {
        ReferenceController.Reference ref = controller.reference();
        assertThat(ref.now()).hasToString("2026-03-05T14:00+07:00");
        assertThat(ref.tutors()).extracting(ReferenceController.Tutor::id).containsExactly("T1", "T2", "T3");
        assertThat(ref.rooms()).containsExactly("R1", "R2", "R3", "R4", "R5", "R6");
        assertThat(ref.students()).hasSize(6);
    }
}
