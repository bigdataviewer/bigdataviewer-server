//Flot Moving Line Chart
$(function () {

    var container = $("#bandwidth-chart-moving");
    var units = ["B", "kB", "MB", "GB", "TB"];

    var maxdata = 0;
    var maximum = container.outerWidth() / 2 || 300;
    maximum = Math.round(maximum);

    var data = [];

    var series = [
        {
            color: "#3e3ef4",
            data: data,
            lines: {
                fill: true
            }
        }
    ];

    function formatter(val, axis) {
        var digitGroups = (maxdata == 0) ? 0 : Math.round(Math.log(maxdata) / Math.log(1024));

        if (digitGroups == 0)
            return "<span style='font-weight: bold'>" + val + " " + units[0] + "</span>";
        else
            return "<span style='font-weight: bold'>" + (val / Math.pow(1024, digitGroups)).toPrecision(3) + " " + units[digitGroups] + "</span>";
    }

    var plot = $.plot(container, series, {
        grid: {
            borderWidth: 1,
            minBorderMargin: 20,
            labelMargin: 10,
            backgroundColor: {
                colors: ["#fff", "#e4f4f4"]
            },
            margin: {
                top: 8,
                bottom: 20,
                left: 20
            },
            markings: function (axes) {
                var markings = [];
                var xaxis = axes.xaxis;
                for (var x = Math.floor(xaxis.min); x < xaxis.max; x += xaxis.tickSize * 2) {
                    markings.push({
                        xaxis: {
                            from: x,
                            to: x + xaxis.tickSize
                        },
                        color: "rgba(245, 245, 255, 0.2)"
                    });
                }
                return markings;
            }
        },
        xaxis: {
            tickFormatter: function () {
                return "";
            }
        },
        yaxis: {
            tickFormatter: formatter,
            min: 0
        },
        legend: {
            show: true
        }
    });

    plot.setData(series);
    plot.draw();

    function onDataReceived(json) {
        if (json.length > 1) {
            for (var i = 0; i < json.length; i++) {
                data.push(json[i]);
            }
        }
        else {
            if (data.length)
                data = data.slice(1);

            data.push(json[0]);
        }

        maxdata = Math.max.apply(Math, data);

        var res = [];
        for (var i = 0; i < data.length; ++i) {
            res.push([i, data[i]])
        }

        series[0].data = res;
        plot.setData(series);
        plot.setupGrid();
        plot.draw();
    }

    function getAjaxData(tf) {
        $.ajax({
            dataType: "json",
            url: "/manager/?op=getTrafficData&tf=" + tf,
            success: onDataReceived
        })
    }

    getAjaxData(maximum);

    setInterval(function () {
        getAjaxData(1);
    }, 5000);
});


//Get Server Status
$(function () {

    function onDataReceived(html) {
        $("#server-info").html(html);
    }

    $.ajax({
        dataType: "html",
        url: "/manager/?op=getServerInfo",
        success: onDataReceived
    });

    setInterval(function () {
        $.ajax({
            dataType: "html",
            url: "/manager/?op=getServerInfo",
            success: onDataReceived
        });
    }, 5000);
});

