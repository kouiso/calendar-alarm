import XCTest
import GoogleMobileAds
@testable import iosApp

@MainActor
final class AdBannerTests: XCTestCase {
    func testBannerRemainsHiddenUntilLoadAndHidesAfterFailure() {
        let coordinator = AdCoordinator()
        let banner = BannerView(adSize: AdSizeBanner)
        XCTAssertFalse(coordinator.bannerLoaded)
        coordinator.bannerViewDidReceiveAd(banner)
        XCTAssertTrue(coordinator.bannerLoaded)
        coordinator.bannerView(banner, didFailToReceiveAdWithError: NSError(domain: "test", code: 1))
        XCTAssertFalse(coordinator.bannerLoaded)
        coordinator.bannerViewDidReceiveAd(banner)
        XCTAssertTrue(coordinator.bannerLoaded)
    }
}
