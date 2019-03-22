package com.pat.obd_scanner.io;

import dagger.Component;

@Component(modules = {SharedPreferencesModule.class})
public interface MyComponent {
    void inject(ObdGatewayService obdGatewayService);
}
