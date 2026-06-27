package com.ba.analyzer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 批量查询辅助函数的纯逻辑测试 (SQL 本身由对真实库的集成验证保证)。 */
class JdbcDataStoreTest {

    @Test
    void inPlaceholdersBuildsCommaSeparatedQuestionMarks() {
        assertEquals("?", JdbcDataStore.inPlaceholders(1));
        assertEquals("?,?,?", JdbcDataStore.inPlaceholders(3));
    }

    @Test
    void partitionSplitsIntoChunks() {
        List<Integer> nums = List.of(1, 2, 3, 4, 5);
        List<List<Integer>> parts = JdbcDataStore.partition(nums, 2);
        assertEquals(3, parts.size());
        assertEquals(List.of(1, 2), parts.get(0));
        assertEquals(List.of(3, 4), parts.get(1));
        assertEquals(List.of(5), parts.get(2));
    }

    @Test
    void partitionExactMultipleHasNoTrailingEmptyChunk() {
        List<List<Integer>> parts = JdbcDataStore.partition(List.of(1, 2, 3, 4), 2);
        assertEquals(2, parts.size());
        assertEquals(List.of(3, 4), parts.get(1));
    }

    @Test
    void partitionSingleChunkWhenSmallerThanSize() {
        List<List<Integer>> parts = JdbcDataStore.partition(List.of(1, 2), 500);
        assertEquals(1, parts.size());
        assertEquals(List.of(1, 2), parts.get(0));
    }
}
