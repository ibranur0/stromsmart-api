package no.ibrahimnur.stromsmart.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class ImportControllerTest {

    @Nested
    @WebMvcTest(controllers = ImportController.class, properties = "stromsmart.import-token=hemmelig")
    class WithToken {

        @Autowired
        private MockMvcTester mvc;

        @MockitoBean
        private PriceImporter importer;

        @Test
        void rightTokenStartsTheImport() {
            when(importer.importMissing()).thenReturn(3);

            assertThat(mvc.post().uri("/internal/import").header("Authorization", "Bearer hemmelig"))
                    .hasStatusOk()
                    .bodyJson()
                    .extractingPath("$.savedDays").isEqualTo(3);
        }

        @Test
        void wrongOrMissingTokenIsRefused() {
            assertThat(mvc.post().uri("/internal/import").header("Authorization", "Bearer feil")).hasStatus(401);
            assertThat(mvc.post().uri("/internal/import").header("Authorization", "hemmelig")).hasStatus(401);
            assertThat(mvc.post().uri("/internal/import")).hasStatus(401);
            verify(importer, never()).importMissing();
        }
    }

    @Nested
    @WebMvcTest(controllers = ImportController.class)
    class WithoutToken {

        @Autowired
        private MockMvcTester mvc;

        @MockitoBean
        private PriceImporter importer;

        @Test
        void endpointDoesNotExist() {
            assertThat(mvc.post().uri("/internal/import").header("Authorization", "Bearer ")).hasStatus(404);
            verify(importer, never()).importMissing();
        }
    }
}
