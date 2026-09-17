package io.github.np.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WeatherTools {

    @Tool(description = "获取指定城市的当前天气信息，包括温度和天气状况")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海") String city) {
        // 实际项目可对接天气API
        return switch (city) {
            case "北京" -> "北京：晴，28°C，湿度40%";
            case "上海" -> "上海：多云，26°C，湿度65%";
            default -> city + "：阴，24°C";
        };
    }
}
