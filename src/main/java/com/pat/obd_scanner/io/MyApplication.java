package com.pat.obd_scanner.io;

import android.app.Application;


public class MyApplication extends Application {
    private MyComponent myComponent;

    @Override
    public void onCreate() {
        super.onCreate();

        myComponent = DaggerMyComponent.builder()
                .sharedPreferencesModule(new SharedPreferencesModule(getApplicationContext()))
                .build();
    }

    public MyComponent getMyComponent() {
        return myComponent;
    }
}