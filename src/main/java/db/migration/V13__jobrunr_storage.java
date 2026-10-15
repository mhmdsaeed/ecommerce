package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jobrunr.storage.sql.common.DatabaseCreator;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * JobRunr's tables (SPEC 14), created by JobRunr's own scripts so they match the library version exactly. They run
 * through Flyway as the schema owner like every other schema change; the application starts JobRunr with
 * {@code skip-create} as {@code app_user}, which may use the tables but not alter them. Platform tables: no
 * {@code tenant_id}, since each job carries its store in its payload.
 *
 * <p>When a JobRunr upgrade brings new table migrations, JobRunr refuses to start until a new Flyway migration
 * calls its creator again; it applies only the scripts that are missing.
 */
public class V13__jobrunr_storage extends BaseJavaMigration {

    /** JobRunr commits its scripts one by one. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        var dataSource = new SingleConnectionDataSource(context.getConnection(), true);
        new DatabaseCreator(dataSource, PostgresStorageProvider.class).runMigrations();
    }
}
