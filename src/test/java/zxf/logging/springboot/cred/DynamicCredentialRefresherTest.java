package zxf.logging.springboot.cred;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicCredentialRefresherTest {

    @Test
    void onSecretChanged_reads_and_applies_credentials() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        DbCredentials creds = new DbCredentials("u", "p");
        when(source.read()).thenReturn(creds);

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        refresher.onSecretChanged();

        verify(applier, times(1)).apply(creds);
    }

    @Test
    void onSecretChanged_swallows_read_exception_without_propagating() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.read()).thenThrow(new java.io.IOException("boom"));

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        // 不应抛出
        refresher.onSecretChanged();

        verify(applier, never()).apply(any());
    }

    @Test
    void start_skips_refresh_when_source_unavailable() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.isAvailable()).thenReturn(false);

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        refresher.start();

        verify(source, never()).read();
        verify(applier, never()).apply(any());
    }
}
