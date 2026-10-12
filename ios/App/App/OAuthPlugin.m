#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(OAuthPlugin, "OAuthPlugin",
    CAP_PLUGIN_METHOD(openOAuth, CAPPluginReturnPromise);
)
