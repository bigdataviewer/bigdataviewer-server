<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta http-equiv="refresh" content="5"/>

    <title>BigDataServer</title>

    <!-- Latest compiled and minified CSS -->
    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.1/css/bootstrap.min.css">

    <!-- Optional theme -->
    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.1/css/bootstrap-theme.min.css">

    <!-- Latest compiled and minified JavaScript -->
    <script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.1/js/bootstrap.min.js"></script>
</head>

<body class="tundra">

This page is refreshed in every 5 secs.<br/>

<div class="contentelement">

    <h1>$title$</h1>

    <hr>

    <table cellspacing="2">
        <tr>
            <th>Bytes sent total:</th>
            <td>$bytesSent$</td>
        </tr>
        <tr>
            <th>Messages per second:</th>
            <td>$msgPerSec$</td>
        </tr>
        <tr>
            <th>Open connections:</th>
            <td>$openConnections$</td>
        </tr>
        <tr>
            <th>Max open connections:</th>
            <td>$maxOpenConnections$</td>
        </tr>
    </table>

    <hr>

    <h1> Datasets: </h1>
    <table cellspacing="2">
        $contexts$
    </table>

    <hr>
    <table cellspacing="2">
        <tr>
            <td>
                $statHtml$
            </td>
        </tr>
    </table>

</div>
</body>
</html>