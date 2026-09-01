# bilibili-subscribes-cleaner

找出你追蹤但已經停更的 Bilibili 帳號，決定要取消追蹤哪些，再匯出它們的 uid，讓你一鍵批次取消追蹤。

<p>
  <a href="https://github.com/1morr/bilibili-subscribes-cleaner-Java/actions/workflows/ci.yml"><img src="https://github.com/1morr/bilibili-subscribes-cleaner-Java/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/1morr/bilibili-subscribes-cleaner-Java" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+">
</p>

[English](README.md) · **繁體中文**

這是一個桌面應用程式，會向 Bilibili 查詢你追蹤的每個帳號最後一次發布內容的時間，並把結果整理成一張可以排序、篩選、匯出的表格。Bilibili 沒有「取消追蹤這些帳號」的 API，所以實際取消追蹤的動作要靠一個油猴腳本完成 —— 這個工具產生的正是它需要的那份清單。

![主視窗](docs/images/main-window.png)

## 需求

- 執行需要 **Java 17 或以上版本**
- 建置需要 **Maven**
- [匯出追蹤清單的油猴腳本](https://greasyfork.org/zh-TW/scripts/428895)，它同時負責產生輸入檔與消費輸出結果

## 建置與執行

```bash
mvn package
java -jar target/bilibili-subscribes-cleaner-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## 使用方式

1. 在油猴腳本裡匯出你的追蹤清單，存成 `export_uids.json`。
2. 打開這個 App，選擇那個檔案，按下 **Process data**。它會一個一個帳號查詢，幾千個追蹤跑起來要花一段時間，所以這一步只想跑一次。
3. 跑完之後，會在你的輸入檔案旁邊寫出 `user_data_cache.json`。下次改按 **Load cache**，就能完全跳過網路請求。
4. 設定停更的天數門檻。表格會隨你輸入即時重新篩選，所以不用重抓資料，就能比較 180 天跟 365 天看起來各是什麼樣子。
5. 排序、勾選你要的列，然後匯出。結果會是一份逗號分隔的 uid 清單，存在 `inactive_users_<timestamp>.txt`。
6. 把它貼回油猴腳本，一次取消追蹤。

匯出模式有：勾選的列、目前顯示的全部、只有沒有分組的帳號，或只有有分組的帳號。「沒有分組」這個模式存在的原因是：分組標籤通常代表你原本就打算留著這個人。

## 不會被匯出的帳號

Bilibili 對被限速的請求回的是**HTTP 200 加一個非零的 `code`**，而不是 HTTP 錯誤，回傳的內容裡也沒有影片清單。如果照字面解讀，看起來就跟「這個帳號從來沒發過任何內容」一模一樣 —— 這正是一個活躍帳號會被誤送進取消追蹤清單的原因。

所以每個帳號最後會落在三種狀態之一，而不是兩種：有影片、確認沒有影片，或是**狀態未知**。未知狀態的帳號會被計算、顯示出來，但永遠不會被匯出。重新跑一次抓取就能解決它們。

基於同樣的理由，這個 App 會自我節流，一旦 Bilibili 開始反制，就直接停掉整個執行，而不是頂著一個已經被限速的 IP，硬把剩下幾千個帳號查完。

## 顯示的欄位

| Column | |
|---|---|
| UID | 帳號的數字 id |
| Name | 來自你匯出的追蹤清單 |
| Group | 你替他們分的任何分組 |
| Days inactive | 距離最新影片的天數 |
| Latest video | 標題，附可點擊的連結 |
| Space | 可點擊的個人主頁連結 |

## 資料與隱私

一切都在本機完成。這個 App 只會讀取你匯出的追蹤清單，呼叫一個**不需要登入**的公開 Bilibili 端點，並把快取與匯出結果寫在你的檔案旁邊。沒有 cookie、沒有憑證，除了 uid 查詢本身之外，什麼都不會離開你的機器。

## 開發

```bash
mvn package          # build the runnable jar
mvn test             # run the tests
```

Java 17、Swing，JSON 處理用 Jackson —— HTTP 走 JDK 內建的 client，所以 Jackson 是唯一的依賴套件。

它取代了 [bilibili-subscribes-cleaner](https://github.com/1morr/bilibili-subscribes-cleaner)，一個做同樣事情的三腳本 Python 版本，該專案已經封存。

## 授權

[MIT](LICENSE)
