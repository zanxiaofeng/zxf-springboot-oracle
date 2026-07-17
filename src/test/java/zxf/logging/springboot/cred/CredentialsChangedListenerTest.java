package zxf.logging.springboot.cred;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialsChangedListenerTest {

    @Test
    void onCredentialsChanged_reads_and_applies_credentials() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        DbCredentials creds = new DbCredentials("u", "p");
        when(source.read()).thenReturn(creds);

        CredentialsChangedListener listener = new CredentialsChangedListener(source, applier);

        listener.onCredentialsChanged(new CredentialsChangedEvent(Instant.now()));

        verify(applier, times(1)).apply(creds);
    }

    @Test
    void onCredentialsChanged_swallows_read_exception_without_propagating() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.read()).thenThrow(new java.io.IOException("boom"));

        CredentialsChangedListener listener = new CredentialsChangedListener(source, applier);

        listener.onCredentialsChanged(new CredentialsChangedEvent(Instant.now()));

        verify(applier, never()).apply(any());
    }

    @Test
    void alignOnStartup_skips_refresh_when_source_unavailable() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.isAvailable()).thenReturn(false);

        CredentialsChangedListener listener = new CredentialsChangedListener(source, applier);

        listener.alignOnStartup();

        verify(source, never()).read();
        verify(applier, never()).apply(any());
    }
}
