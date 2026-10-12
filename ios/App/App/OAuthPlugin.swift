import Foundation
import Capacitor
import AuthenticationServices

@objc(OAuthPlugin)
public class OAuthPlugin: CAPPlugin, ASWebAuthenticationPresentationContextProviding {

    private var authSession: ASWebAuthenticationSession?

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return UIApplication.shared.windows.first { $0.isKeyWindow } ?? UIWindow()
    }

    @objc func openOAuth(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL is required")
            return
        }

        guard let url = URL(string: urlString) else {
            call.reject("Invalid URL")
            return
        }

        let callbackScheme = call.getString("callbackScheme") ?? "ai.employ.app"

        DispatchQueue.main.async {
            self.authSession = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: callbackScheme
            ) { callbackURL, error in
                if let error = error {
                    if (error as NSError).code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                        call.resolve(["cancelled": true])
                    } else {
                        call.reject(error.localizedDescription)
                    }
                    return
                }

                if let callbackURL = callbackURL {
                    call.resolve([
                        "url": callbackURL.absoluteString,
                        "cancelled": false
                    ])
                } else {
                    call.resolve(["cancelled": true])
                }
            }

            self.authSession?.presentationContextProvider = self
            self.authSession?.prefersEphemeralWebBrowserSession = false
            self.authSession?.start()
        }
    }
}
