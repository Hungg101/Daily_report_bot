(function () {
    "use strict";

    const {useCallback, useEffect, useMemo, useRef, useState} = React;
    const createRoot = ReactDOM.createRoot;
    const h = React.createElement;

    const templates = [
        {
            label: "Hoàn thành",
            value: "Hôm nay tôi đã hoàn thành:\n- \n\nKế hoạch tiếp theo:\n- "
        },
        {
            label: "Đang xử lý",
            value: "Hôm nay tôi đang xử lý:\n- \n\nVướng mắc:\n- "
        },
        {
            label: "Cần hỗ trợ",
            value: "Tôi cần hỗ trợ về:\n- \n\nMong muốn:\n- "
        }
    ];

    function readTelegramContext() {
        const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
        const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
        const platform = tg && typeof tg.platform === "string" ? tg.platform : "";
        const hasInitData = initData.length > 0;
        const isTelegramShell = Boolean(tg && platform && platform !== "unknown");

        return {
            tg,
            hasInitData,
            isTelegramShell,
            canSendData: Boolean(tg && typeof tg.sendData === "function" && (hasInitData || isTelegramShell))
        };
    }

    function App() {
        const telegram = useMemo(readTelegramContext, []);
        const [content, setContent] = useState("");
        const [status, setStatus] = useState(() => telegram.canSendData
            ? {message: "Sẵn sàng gửi về bot Telegram.", type: "success"}
            : {message: "Bản xem thử: dữ liệu chưa gửi về bot và database.", type: "warning"});
        const contentRef = useRef(content);

        const dateText = useMemo(() => new Intl.DateTimeFormat("vi-VN", {
            weekday: "short",
            day: "2-digit",
            month: "2-digit",
            year: "numeric"
        }).format(new Date()), []);

        const identity = useMemo(() => {
            if (!telegram.tg) {
                return "Đang xem thử trên trình duyệt";
            }

            const user = telegram.tg.initDataUnsafe && telegram.tg.initDataUnsafe.user
                ? telegram.tg.initDataUnsafe.user
                : null;

            if (!user) {
                return telegram.isTelegramShell
                    ? "Đang mở trong Telegram"
                    : "Đang xem thử trên trình duyệt";
            }

            const displayName = [user.first_name, user.last_name].filter(Boolean).join(" ");
            return user.username ? `${displayName || "Telegram user"} (@${user.username})` : displayName;
        }, [telegram]);

        const runtimeText = telegram.canSendData ? "Telegram" : "Xem thử";
        const submitButtonText = telegram.canSendData ? "Gửi báo cáo" : "Lưu nháp";

        useEffect(() => {
            contentRef.current = content;
        }, [content]);

        const submitReport = useCallback(() => {
            const trimmedContent = contentRef.current.trim();
            if (!trimmedContent) {
                setStatus({
                    message: "Nội dung báo cáo không được để trống.",
                    type: "error"
                });
                return;
            }

            const payload = JSON.stringify({
                type: "daily_report",
                content: trimmedContent,
                submittedAt: new Date().toISOString()
            });

            if (telegram.canSendData) {
                try {
                    telegram.tg.sendData(payload);
                    setStatus({
                        message: "Đã gửi báo cáo về bot.",
                        type: "success"
                    });

                    if (typeof telegram.tg.close === "function") {
                        window.setTimeout(() => telegram.tg.close(), 450);
                    }
                    return;
                } catch (error) {
                    console.error("Cannot send data to Telegram WebApp.", error);
                    setStatus({
                        message: "Chưa gửi được về bot. Hãy mở bằng nút /miniapp trong chat Telegram.",
                        type: "error"
                    });
                    return;
                }
            }

            window.localStorage.setItem("daily-report-miniapp-demo", payload);
            setStatus({
                message: "Đã lưu nháp trong trình duyệt. Database chưa cập nhật.",
                type: "warning"
            });
        }, [telegram]);

        useEffect(() => {
            if (!telegram.tg) {
                return;
            }

            try {
                telegram.tg.ready();
                telegram.tg.expand();
            } catch (error) {
                console.warn("Telegram WebApp ready/expand failed.", error);
            }
        }, [telegram]);

        useEffect(() => {
            if (!telegram.canSendData || !telegram.tg.MainButton) {
                return undefined;
            }

            const mainButton = telegram.tg.MainButton;
            mainButton.setText("Gửi báo cáo");
            mainButton.onClick(submitReport);

            return () => {
                if (typeof mainButton.offClick === "function") {
                    mainButton.offClick(submitReport);
                }
            };
        }, [submitReport, telegram]);

        useEffect(() => {
            if (!telegram.canSendData || !telegram.tg.MainButton) {
                return;
            }

            if (content.trim()) {
                telegram.tg.MainButton.show();
            } else {
                telegram.tg.MainButton.hide();
            }
        }, [content, telegram]);

        const appendTemplate = (template) => {
            setContent((current) => current.trim()
                ? `${current.trim()}\n\n${template}`
                : template);
            setStatus(telegram.canSendData
                ? {message: "Sẵn sàng gửi về bot Telegram.", type: "success"}
                : {message: "Bản xem thử: dữ liệu chưa gửi về bot và database.", type: "warning"});
        };

        const clearContent = () => {
            setContent("");
            setStatus({message: "", type: ""});
        };

        return h("main", {className: "app"}, [
            h("section", {className: "topbar", "aria-label": "Thông tin báo cáo", key: "topbar"}, [
                h("div", {key: "title"}, [
                    h("p", {className: "eyebrow", key: "eyebrow"}, "Daily Report"),
                    h("h1", {key: "heading"}, "Báo cáo hôm nay"),
                    h("div", {className: "identity", key: "identity"}, identity)
                ]),
                h("div", {className: "meta", key: "meta"}, [
                    h("div", {className: "date-pill", key: "date"}, dateText),
                    h("div", {className: "runtime-pill", key: "runtime"}, runtimeText)
                ])
            ]),
            h("section", {className: "form", "aria-label": "Nội dung báo cáo", key: "form"}, [
                h("div", {className: "form-head", key: "form-head"}, [
                    h("label", {htmlFor: "reportContent", key: "label"}, "Nội dung"),
                    h("div", {className: "count", key: "count"}, `${content.trim().length} ký tự`)
                ]),
                h("textarea", {
                    id: "reportContent",
                    placeholder: "Hôm nay bạn đã làm gì?",
                    value: content,
                    onChange: (event) => {
                        setContent(event.target.value);
                        setStatus({message: "", type: ""});
                    },
                    key: "textarea"
                }),
                h("div", {className: "templates", "aria-label": "Mẫu nhanh", key: "templates"},
                    templates.map((template) => h("button", {
                        type: "button",
                        className: "template-button",
                        onClick: () => appendTemplate(template.value),
                        key: template.label
                    }, template.label))
                )
            ]),
            h("section", {className: "footer", "aria-label": "Gửi báo cáo", key: "footer"}, [
                h("div", {className: `status ${status.type}`.trim(), key: "status"}, status.message),
                h("button", {type: "button", onClick: clearContent, key: "clear"}, "Xóa"),
                h("button", {
                    type: "button",
                    className: "primary",
                    disabled: !content.trim(),
                    onClick: submitReport,
                    key: "submit"
                }, submitButtonText)
            ])
        ]);
    }

    createRoot(document.getElementById("root")).render(h(App));
}());
