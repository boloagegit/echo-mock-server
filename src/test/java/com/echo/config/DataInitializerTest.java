package com.echo.config;

import com.echo.entity.HttpRule;
import com.echo.entity.Response;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.JmsRuleRepository;
import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock private ResponseRepository responseRepository;
    @Mock private HttpRuleRepository httpRuleRepository;
    @Mock private JmsRuleRepository jmsRuleRepository;

    @InjectMocks
    private DataInitializer dataInitializer;

    @Test
    void run_shouldInitializeData_whenNoExistingRules() {
        when(httpRuleRepository.count()).thenReturn(0L);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response r = invocation.getArgument(0);
            r.setId(System.nanoTime());
            return r;
        });
        when(httpRuleRepository.save(any(HttpRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jmsRuleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jmsRuleRepository.count()).thenReturn(3L);

        dataInitializer.run();

        verify(responseRepository, atLeast(10)).save(any(Response.class));
        verify(httpRuleRepository, atLeast(10)).save(any(HttpRule.class));
        verify(jmsRuleRepository, atLeast(3)).save(any());
    }

    @Test
    void run_shouldSkipInitialization_whenDataExists() {
        when(httpRuleRepository.count()).thenReturn(5L);

        dataInitializer.run();

        verify(responseRepository, never()).save(any());
        verify(httpRuleRepository, never()).save(any());
        verify(jmsRuleRepository, never()).save(any());
    }
}
