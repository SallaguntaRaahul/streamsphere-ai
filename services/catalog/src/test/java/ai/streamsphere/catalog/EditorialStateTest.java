package ai.streamsphere.catalog;

import static ai.streamsphere.catalog.EditorialState.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EditorialStateTest {
    @Test
    void publishRequiresApproval() {
        assertThat(DRAFT.canMoveTo(PUBLISHED)).isFalse();
        assertThat(IN_REVIEW.canMoveTo(PUBLISHED)).isFalse();
        assertThat(APPROVED.canMoveTo(PUBLISHED)).isTrue();
    }

    @Test
    void removedIsTerminal() {
        for (EditorialState s : values()) assertThat(REMOVED.canMoveTo(s)).isFalse();
    }

    @Test
    void anyLiveStateCanBeTakenDown() {
        for (EditorialState s : new EditorialState[]{DRAFT, IN_REVIEW, APPROVED, REJECTED, PUBLISHED}) assertThat(s.canMoveTo(REMOVED)).isTrue();
    }

    @Test
    void onlyDraftAndRejectedAreEditable() {
        assertThat(DRAFT.editable()).isTrue();
        assertThat(REJECTED.editable()).isTrue();
        assertThat(PUBLISHED.editable()).isFalse();
        assertThat(IN_REVIEW.editable()).isFalse();
    }
}
