package com.friends.common;

import org.junit.jupiter.api.Nested;

/** Re-runs the storage and core suites on MySQL/MariaDB; skipped unless -Dfriends.mysql is set. */
class MysqlTest {
    @Nested
    class StorageOnMysql extends StorageTest {
        @Override
        boolean mysql() {
            return true;
        }
    }

    @Nested
    class CoreOnMysql extends FriendsTest {
        @Override
        boolean mysql() {
            return true;
        }
    }
}
