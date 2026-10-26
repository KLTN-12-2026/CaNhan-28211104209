import { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../api/client";
import { createRequestGeneration } from "../utils/requestGeneration";
import { selectApiRequestState } from "../utils/apiRequestState";
import { getErrorMessage } from "../utils/roles";

export function useApiData(path, enabled = true) {
  const [result, setResult] = useState(null);
  const [revision, setRevision] = useState(0);
  const requestKey = useMemo(
    () => ({ path, revision, enabled }),
    [path, revision, enabled],
  );
  const [requestGeneration] = useState(createRequestGeneration);

  useEffect(() => {
    if (!enabled) return;
    const generation = requestGeneration.begin();

    apiClient.get(path).then(
      ({ data }) => {
        if (requestGeneration.isCurrent(generation)) {
          setResult({ requestKey, data, error: "" });
        }
      },
      (requestError) => {
        if (requestGeneration.isCurrent(generation)) {
          setResult({
            requestKey,
            data: null,
            error: getErrorMessage(requestError, "Không thể tải dữ liệu."),
          });
        }
      },
    );

    return () => {
      requestGeneration.invalidate(generation);
    };
  }, [enabled, path, requestGeneration, requestKey]);

  const reload = useCallback(() => {
    setRevision((current) => current + 1);
  }, []);

  return {
    ...selectApiRequestState(result, requestKey, enabled),
    reload,
  };
}