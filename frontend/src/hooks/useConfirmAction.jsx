import { useCallback, useEffect, useRef, useState } from "react";
import Modal from "../components/common/Modal";

export default function useConfirmAction() {
  const [message, setMessage] = useState("");
  const resolver = useRef(null);
  useEffect(() => () => { resolver.current?.(false); resolver.current = null; }, []);
  const confirm = useCallback(text => {
    if (resolver.current) return Promise.resolve(false);
    return new Promise(resolve => { resolver.current = resolve; setMessage(text); });
  }, []);
  function settle(accepted) {
    const resolve = resolver.current;
    resolver.current = null;
    setMessage("");
    resolve?.(accepted);
  }
  const confirmation = <Modal open={Boolean(message)} title="Xác nhận thao tác" onClose={() => settle(false)} footer={<><button type="button" className="btn-secondary" onClick={() => settle(false)}>Hủy</button><button type="button" className="btn-danger" onClick={() => settle(true)}>Xác nhận</button></>}><p className="text-sm leading-6">{message}</p></Modal>;
  return { confirm, confirmation };
}
