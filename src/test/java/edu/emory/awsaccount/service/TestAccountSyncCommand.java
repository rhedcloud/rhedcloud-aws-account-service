package edu.emory.awsaccount.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestAccountSyncCommand {

    @BeforeEach
    void setUp() throws Exception {
    }

    @Test
    void test() {
        assertEquals("gwang28@emory.edu", AccountSyncCommand.parseAuthUser("gwang28@emory.edu/10.110.32.14"));
    }

}
