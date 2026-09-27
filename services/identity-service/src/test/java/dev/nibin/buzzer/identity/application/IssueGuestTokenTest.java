package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueGuestTokenTest {

    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final IssueGuestToken issueGuestToken = new IssueGuestToken(accessTokenIssuer);

    @Test
    void issuesATokenForTheStrippedNickname() {
        AccessToken issued = new AccessToken("signed.jwt.value", Duration.ofHours(3));
        when(accessTokenIssuer.issueForGuest(any(), eq("Quiz Fan"))).thenReturn(issued);

        assertThat(issueGuestToken.issue("  Quiz Fan ")).isSameAs(issued);
    }

    @Test
    void everyRequestIsANewGuest() {
        issueGuestToken.issue("Quiz Fan");
        issueGuestToken.issue("Quiz Fan");

        ArgumentCaptor<UUID> guestIds = ArgumentCaptor.forClass(UUID.class);
        verify(accessTokenIssuer, times(2)).issueForGuest(guestIds.capture(), anyString());
        assertThat(guestIds.getAllValues().get(0)).isNotEqualTo(guestIds.getAllValues().get(1));
    }
}
