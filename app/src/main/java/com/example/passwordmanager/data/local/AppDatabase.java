package com.example.passwordmanager.data.local;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {CredentialEntity.class},
        version = 4,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    static final Migration MIGRATION_3_4 = new Migration(3,4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE credentials " +
                    "ADD COLUMN autofillDomain TEXT"
            );

            db.execSQL("ALTER TABLE credentials " +
                    "ADD COLUMN autofillPackage TEXT"
            );
        }
    };

    public abstract CredentialDao credentialDao();
    private static volatile AppDatabase INSTANCE;
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "credential_manager.db"
                    )
                            .addMigrations(MIGRATION_3_4)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

}
