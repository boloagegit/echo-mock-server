package com.echo.service;

import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A large number of ID do not form a single IN list (SQL Server/Oracle have parameter upper limits), and the result must be the same as the original. */
class RuleServiceValidResponseIdsTest {

    private final ResponseRepository responses = mock(ResponseRepository.class);
    private final RuleService ruleService = new RuleService(null, responses, null, null,
            java.util.Optional.empty(), java.util.Optional.empty(), null);

    @Test
    void smallSetsStillUseInListQuery() {
        Set<Long> ids = LongStream.rangeClosed(1, RuleService.MAX_IN_LIST_IDS).boxed().collect(Collectors.toSet());
        when(responses.findExistingIds(any())).thenReturn(List.of(1L, 2L));

        assertThat(ruleService.getValidResponseIds(ids)).containsExactlyInAnyOrder(1L, 2L);
        verify(responses, never()).findAllIds();
    }

    @Test
    void largeSetsIntersectWithAllIdsInMemory() {
        Set<Long> ids = LongStream.rangeClosed(1, RuleService.MAX_IN_LIST_IDS + 1).boxed()
                .collect(Collectors.toCollection(HashSet::new));
        when(responses.findAllIds()).thenReturn(List.of(1L, 7L, 99_999L));

        assertThat(ruleService.getValidResponseIds(ids)).containsExactlyInAnyOrder(1L, 7L);
        verify(responses, never()).findExistingIds(any());
    }
}
