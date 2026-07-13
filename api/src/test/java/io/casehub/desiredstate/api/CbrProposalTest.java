package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrProposalTest {

    @Test
    void validProposal() {
        var proposal = new CbrProposal("src-1", CbrPath.FAULT,
            Set.of(new NodeId("n1")), Instant.now());
        assertThat(proposal.sourceId()).isEqualTo("src-1");
        assertThat(proposal.path()).isEqualTo(CbrPath.FAULT);
        assertThat(proposal.affectedNodeIds()).containsExactly(new NodeId("n1"));
    }

    @Test
    void nullSourceId_throws() {
        assertThatThrownBy(() -> new CbrProposal(null, CbrPath.FAULT,
            Set.of(new NodeId("n1")), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullPath_throws() {
        assertThatThrownBy(() -> new CbrProposal("src-1", null,
            Set.of(new NodeId("n1")), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTimestamp_throws() {
        assertThatThrownBy(() -> new CbrProposal("src-1", CbrPath.FAULT,
            Set.of(new NodeId("n1")), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void affectedNodeIds_defensiveCopy() {
        var mutable = new HashSet<>(Set.of(new NodeId("n1")));
        var proposal = new CbrProposal("src-1", CbrPath.FAULT, mutable, Instant.now());
        mutable.add(new NodeId("n2"));
        assertThat(proposal.affectedNodeIds()).hasSize(1);
    }
}
