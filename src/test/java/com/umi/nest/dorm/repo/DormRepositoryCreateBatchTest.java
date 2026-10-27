package com.umi.nest.dorm.repo;

import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.solver.SolveResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 回归：dorm_batch.block_count 曾被写成阻塞对数（实测库里「分块 1716」，实际 26 块）。
 */
@DisplayName("DormRepository.createBatch · 列与值对应")
class DormRepositoryCreateBatchTest {

    @Test
    @DisplayName("block_count 写的是分块数，不是阻塞对数")
    void blockCountColumnGetsBlockCount() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<PreparedStatementCreator> creator = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        when(jdbc.update(creator.capture(), any(KeyHolder.class))).thenAnswer(inv -> {
            ((GeneratedKeyHolder) inv.getArgument(1)).getKeyList().add(Map.of("id", 42L));
            return 1;
        });

        SolveResult result = new SolveResult("nest-test", Solution.empty(), 10L, 0, 0, 0.0,
                1716, 26, 52, List.of(), List.of(), Map.of());
        ProblemInstance problem = new ProblemInstance(List.of(), List.of(), Map.of(), Map.of());

        long id = new DormRepository(jdbc).createBatch("t", result, problem, "{}");
        assertEquals(42L, id);

        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(ps);
        creator.getValue().createPreparedStatement(con);

        verify(ps).setInt(10, 26);   // block_count
        verify(ps).setInt(11, 52);   // max_block_size
        verify(ps, never()).setInt(10, 1716);
    }
}
