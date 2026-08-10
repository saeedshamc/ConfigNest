package com.example.data

import com.example.model.ConfigSource
import com.example.model.SourceType

object DefaultSources {

    val LIST = listOf(
        ConfigSource("gh_1", "MatinGhanbari Subscription", "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt", SourceType.GITHUB),
        ConfigSource("gh_2", "barry-far Base64 Sub", "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Config_base64_Sub.txt", SourceType.GITHUB),
        ConfigSource("gh_3", "Epodonios All Configs", "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt", SourceType.GITHUB),
        ConfigSource("gh_4", "Delta-Kronecker All Configs", "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/main/config/all_configs.txt", SourceType.GITHUB),
        ConfigSource("gh_5", "sakha1370 OpenRay Proxies", "https://raw.githubusercontent.com/sakha1370/OpenRay/main/output/all_valid_proxies.txt", SourceType.GITHUB),
        ConfigSource("gh_6", "wuqb2i4f Mix URI Base64", "https://raw.githubusercontent.com/wuqb2i4f/xray-config-toolkit/main/output/base64/mix-uri", SourceType.GITHUB),
        ConfigSource("gh_7", "V2RayRoot VLess", "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vless.txt", SourceType.GITHUB),
        ConfigSource("gh_8", "sevcator VLess", "https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vl.txt", SourceType.GITHUB),
        ConfigSource("gh_9", "V2RAY_SUB Pool 1", "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/V2RAY_SUB/main/v2ray_configs_no1.txt", SourceType.GITHUB),
        ConfigSource("gh_10", "V2RAY_SUB Pool 2", "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/V2RAY_SUB/main/v2ray_configs_no2.txt", SourceType.GITHUB),
        ConfigSource("gh_11", "V2RAY_SUB Pool 3", "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/V2RAY_SUB/main/v2ray_configs_no3.txt", SourceType.GITHUB),
        ConfigSource("gh_12", "V2RAY_SUB Pool 4", "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/V2RAY_SUB/main/v2ray_configs_no4.txt", SourceType.GITHUB),
        ConfigSource("gh_13", "V2RAY_SUB Pool 5", "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/V2RAY_SUB/main/v2ray_configs_no5.txt", SourceType.GITHUB),
        ConfigSource("gh_14", "OneTwoThree Manager", "https://manager.onetwothree123.ir/", SourceType.GITHUB),
        ConfigSource("gh_15", "OneTwoThree Office", "https://office.onetwothree123.ir/", SourceType.GITHUB),
        ConfigSource("tg_1", "Telegram @ev2rayy", "https://t.me/s/ev2rayy", SourceType.TELEGRAM),
        ConfigSource("tg_2", "Telegram @ZibaNabz", "https://t.me/s/ZibaNabz", SourceType.TELEGRAM),
        ConfigSource("tg_3", "Telegram @Raydikalx", "https://t.me/s/Raydikalx", SourceType.TELEGRAM)
    )
}
