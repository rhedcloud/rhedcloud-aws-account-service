package edu.emory.awsaccount.service;

//import static org.junit.jupiter.api.Assertions.*;

import org.junit.Test;

public class S3HelperTest {

    // @BeforeEach
    public void setUp() throws Exception {
    }

    @Test
    public void testRead() {
        S3Helper helper = new S3Helper(null);
        helper.readDeletedAccounts("DEV-" + AccountCsvSyncCommand.deletedAccountsFileName);
    }

    @Test
    public void testUpload() {
        new S3Helper(null).uploadToS3("GeorgeTest.txt", "README.md");
    }
}
