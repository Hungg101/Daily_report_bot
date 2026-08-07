(() => {
    "use strict";

    const telegram = window.Telegram?.WebApp;
    const mainButton = telegram?.MainButton;
    const form = document.getElementById("report-form");
    const preview = document.getElementById("preview");
    const titleInput = document.getElementById("title");
    const collaboratorsInput = document.getElementById("collaborators");
    const contentInput = document.getElementById("content");
    const soloButton = document.getElementById("solo");
    const editButton = document.getElementById("edit");
    const confirmButton = document.getElementById("confirm");
    const status = document.getElementById("status");
    const fields = [titleInput, collaboratorsInput, contentInput];
    let draft = null;
    let submitting = false;

    function setStatus(message, type = "") {
        status.textContent = message;
        status.className = `status ${type}`.trim();
    }

    function showEdit() {
        preview.hidden = true;
        form.hidden = false;
        mainButton?.hide();
        titleInput.focus();
    }

    soloButton.addEventListener("click", () => {
        collaboratorsInput.value = "Không có";
        collaboratorsInput.setCustomValidity("");
    });

    fields.forEach((field) => field.addEventListener("input", () => field.setCustomValidity("")));

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        fields.forEach((field) => field.setCustomValidity(
            field.value.trim() ? "" : "Vui lòng không để trống."));
        if (!form.reportValidity()) return;

        draft = {
            title: titleInput.value.trim(),
            collaborators: collaboratorsInput.value.trim(),
            content: contentInput.value.trim()
        };
        document.getElementById("preview-title").textContent = draft.title;
        document.getElementById("preview-collaborators").textContent = draft.collaborators;
        document.getElementById("preview-content").textContent = draft.content;
        form.hidden = true;
        preview.hidden = false;
        setStatus("");
        mainButton?.show();
        confirmButton.focus();
    });

    editButton.addEventListener("click", showEdit);

    async function confirmSubmission() {
        if (submitting || !draft) return;
        submitting = true;
        confirmButton.disabled = true;
        editButton.disabled = true;
        if (mainButton) {
            mainButton.disable();
            mainButton.showProgress();
        }
        setStatus("Đang gửi báo cáo...");

        try {
            const response = await fetch("/api/miniapp/reports", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-Telegram-Init-Data": telegram?.initData || ""
                },
                body: JSON.stringify(draft)
            });
            let message = "";
            try {
                const result = await response.json();
                message = typeof result.message === "string" ? result.message : "";
            } catch {
                // A bounded fallback keeps malformed responses from being shown as success.
            }

            if (response.status === 201) {
                preview.hidden = true;
                mainButton?.hide();
                setStatus(message || "Đã lưu báo cáo.", "success");
                return;
            }

            showEdit();
            setStatus(message || "Không thể lưu báo cáo. Vui lòng kiểm tra và thử lại.", "error");
        } catch {
            showEdit();
            setStatus(
                "Chưa thể xác định báo cáo đã được lưu hay chưa. Ứng dụng sẽ không tự gửi lại; hãy kiểm tra trước khi thử lại.",
                "error");
        } finally {
            submitting = false;
            confirmButton.disabled = false;
            editButton.disabled = false;
            if (mainButton) {
                mainButton.hideProgress();
                mainButton.enable();
            }
        }
    }

    confirmButton.addEventListener("click", confirmSubmission);
    if (telegram) {
        telegram.ready();
        telegram.expand();
    }
    if (mainButton) {
        mainButton.setText("Xác nhận gửi");
        mainButton.onClick(confirmSubmission);
        mainButton.hide();
    }
})();
